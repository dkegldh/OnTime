package com.ontime.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.auth.LoginUserArgumentResolver;
import com.ontime.group.domain.FineType;
import com.ontime.group.domain.GroupType;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Weekday;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.domain.MeetingStatus;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.support.MutableClock;
import com.ontime.support.MutableClockConfig;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 정기 모임 생성 API 통합 테스트. 실제 PostgreSQL에 회차와 참석 행이 만들어지는지, 이후 참여/나가기가 기간 중간 시각에 맞게
 * 동작하는지 확인한다. 서버 시각은 옮길 수 있는 Clock 빈 (시작: 2026-11-25(수) 12:00 KST).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfig.class})
class RecurringMeetingGroupControllerTest {

  private static final String BASE = "/api/v1/meeting-groups";
  private static final String USER_HEADER = LoginUserArgumentResolver.USER_ID_HEADER;
  private static final Instant NOW = MutableClockConfig.NOW;

  // 기본 요청의 회차: 2026-11-30(월) ~ 2026-12-13(일), 월·수 19:00 KST (= 10:00 UTC)
  private static final Instant FIRST = Instant.parse("2026-11-30T10:00:00Z");
  private static final Instant SECOND = Instant.parse("2026-12-02T10:00:00Z");
  private static final Instant THIRD = Instant.parse("2026-12-07T10:00:00Z");
  private static final Instant FOURTH = Instant.parse("2026-12-09T10:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private MeetingRepository meetingRepository;
  @Autowired private AttendanceRepository attendanceRepository;

  private Long ownerId;
  private Long guestId;

  @BeforeEach
  void setUp() {
    clock.setInstant(NOW);
    ownerId = userRepository.save(User.create("방장")).getId();
    guestId = userRepository.save(User.create("손님")).getId();
  }

  @AfterEach
  void tearDown() {
    attendanceRepository.deleteAllInBatch();
    meetingRepository.deleteAllInBatch();
    inviteCodeRepository.deleteAllInBatch();
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  // ---- 헬퍼 ----

  /** 정기 모임 정상 요청. 요일은 순서를 섞고 중복을 넣어 보낸다. */
  private static Map<String, Object> validRequest() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "RECURRING");
    body.put("name", "월수 스터디");
    body.put("placeName", "강남역 11번 출구");
    body.put("latitude", 37.4979);
    body.put("longitude", 127.0276);
    body.put("startDate", "2026-11-30");
    body.put("endDate", "2026-12-13");
    body.put("daysOfWeek", List.of("WED", "MON", "WED"));
    body.put("meetingTime", "19:00");
    body.put("graceMinutes", 5);
    body.put("absentAfterMinutes", 60);
    body.put("arrivalRadiusM", 200);
    body.put("fineType", "MONEY");
    body.put("fineAmount", 3000);
    return body;
  }

  /** 정상 요청에 일부 값을 덮어쓴다. 값이 null이면 그 필드를 뺀다. */
  private static Map<String, Object> requestWith(Object... keyValues) {
    Map<String, Object> body = validRequest();
    for (int i = 0; i < keyValues.length; i += 2) {
      String key = (String) keyValues[i];
      Object value = keyValues[i + 1];
      if (value == null) {
        body.remove(key);
      } else {
        body.put(key, value);
      }
    }
    return body;
  }

  private ResultActions create(Long userId, Map<String, Object> body) throws Exception {
    return mockMvc.perform(
        post(BASE)
            .header(USER_HEADER, userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private Long createGroup(Map<String, Object> body) throws Exception {
    String json =
        create(ownerId, body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private String issueCode(Long groupId) throws Exception {
    String json =
        mockMvc
            .perform(
                post(BASE + "/" + groupId + "/invite-codes")
                    .header(USER_HEADER, ownerId.toString()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("code").asString();
  }

  private void join(Long userId, String code) throws Exception {
    mockMvc
        .perform(
            post(BASE + "/members")
                .header(USER_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("inviteCode", code))))
        .andExpect(status().isCreated());
  }

  private List<Meeting> meetingsByTime() {
    return meetingRepository.findAll().stream()
        .sorted(Comparator.comparing(Meeting::getMeetingAt))
        .toList();
  }

  /** 사용자의 참석 행이 있는 회차의 약속 일시를 이른 순으로. */
  private List<Instant> attendedMeetingTimes(Long userId) {
    return jdbcTemplate.queryForList(
        """
        select m.meeting_at from attendances a join meetings m on m.id = a.meeting_id
        where a.user_id = ? order by m.meeting_at
        """,
        Instant.class,
        userId);
  }

  private void assertNothingSaved() {
    assertThat(meetingGroupRepository.count()).isZero();
    assertThat(groupMemberRepository.count()).isZero();
    assertThat(meetingRepository.count()).isZero();
    assertThat(attendanceRepository.count()).isZero();
  }

  // ---- 생성 ----

  @Test
  @DisplayName("정기 모임을 만들면 201과 기간·요일이 담긴 상세를 돌려주고, 요일은 중복 없이 월요일부터 정렬된다")
  void create_recurring_returnsDetailWithPeriodAndSortedDays() throws Exception {
    // when
    ResultActions result = create(ownerId, validRequest());

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", startsWith(BASE + "/")))
        .andExpect(jsonPath("$.type").value("RECURRING"))
        .andExpect(jsonPath("$.startDate").value("2026-11-30"))
        .andExpect(jsonPath("$.endDate").value("2026-12-13"))
        .andExpect(jsonPath("$.daysOfWeek", contains("MON", "WED")))
        .andExpect(jsonPath("$.meetingTime").value("19:00:00"))
        .andExpect(jsonPath("$.myRole").value("OWNER"));
  }

  @Test
  @DisplayName("정기 모임을 만들면 기간 안의 고른 요일마다 회차가 SCHEDULED로 만들어지고 그룹 규칙이 복사된다")
  void create_recurring_createsMeetingPerChosenDayWithSnapshot() throws Exception {
    // when
    Long groupId =
        createGroup(requestWith("fineType", "TEXT", "fineAmount", null, "penaltyText", "커피 사기"));

    // then
    List<Meeting> meetings = meetingsByTime();
    assertThat(meetings).extracting(Meeting::getMeetingAt).containsExactly(FIRST, SECOND, THIRD, FOURTH);
    assertThat(meetings)
        .allSatisfy(
            m -> {
              assertThat(m.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
              assertThat(m.getPlace().getName()).isEqualTo("강남역 11번 출구");
              assertThat(m.getPlace().getLatitude()).isEqualTo(37.4979);
              assertThat(m.getPlace().getLongitude()).isEqualTo(127.0276);
              assertThat(m.getArrivalRule().getGraceMinutes()).isEqualTo(5);
              assertThat(m.getArrivalRule().getAbsentAfterMinutes()).isEqualTo(60);
              assertThat(m.getArrivalRule().getArrivalRadiusM()).isEqualTo(200);
              assertThat(m.getFineRule().getFineType()).isEqualTo(FineType.TEXT);
              assertThat(m.getFineRule().getFineAmount()).isZero();
              assertThat(m.getFineRule().getPenaltyText()).isEqualTo("커피 사기");
            });
    Long linkedGroups =
        jdbcTemplate.queryForObject(
            "select count(*) from meetings where group_id = ?", Long.class, groupId);
    assertThat(linkedGroups).isEqualTo(4L);
  }

  @Test
  @DisplayName("정기 모임을 만들면 방장이 모든 회차에 READY로 등록된다")
  void create_recurring_registersOwnerReadyInEveryMeeting() throws Exception {
    // when
    createGroup(validRequest());

    // then
    assertThat(attendedMeetingTimes(ownerId)).containsExactly(FIRST, SECOND, THIRD, FOURTH);
    assertThat(attendanceRepository.findAll())
        .extracting(a -> a.getStatus())
        .containsOnly(AttendanceStatus.READY);
  }

  @Test
  @DisplayName("기간과 요일은 end_date와 요일 비트마스크(월=1, 수=4 → 5)로 저장되고 다시 읽으면 같다")
  void create_recurring_storesEndDateAndDayBitmask() throws Exception {
    // when
    Long groupId = createGroup(validRequest());

    // then
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select type, end_date, days_of_week from meeting_groups where id = ?", groupId);
    assertThat(row.get("type")).isEqualTo("RECURRING");
    assertThat(row.get("end_date").toString()).isEqualTo("2026-12-13");
    assertThat(row.get("days_of_week")).isEqualTo(5);
    MeetingGroup saved = meetingGroupRepository.findById(groupId).orElseThrow();
    assertThat(saved.getType()).isEqualTo(GroupType.RECURRING);
    assertThat(saved.getDaysOfWeek().toList()).containsExactly(Weekday.MON, Weekday.WED);
  }

  @Test
  @DisplayName("기간이 90일이고 매일이면 회차와 방장 참석 행이 90개씩 만들어진다")
  void create_ninetyDaysEveryDay_createsNinetyMeetings() throws Exception {
    // given: 2026-11-30 ~ 2027-02-27 (양 끝 포함 90일)
    Map<String, Object> body =
        requestWith(
            "endDate", "2027-02-27",
            "daysOfWeek", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));

    // when
    createGroup(body);

    // then
    List<Meeting> meetings = meetingsByTime();
    assertThat(meetings).hasSize(90);
    assertThat(meetings.get(0).getMeetingAt()).isEqualTo(FIRST);
    assertThat(meetings.get(89).getMeetingAt()).isEqualTo(Instant.parse("2027-02-27T10:00:00Z"));
    assertThat(attendanceRepository.count()).isEqualTo(90);
  }

  @Test
  @DisplayName("오늘이 고른 요일이어도 만나는 시각이 서버 시각과 같으면 오늘 회차는 만들지 않는다")
  void create_todayMeetingAtServerTime_skipsToday() throws Exception {
    // given: 서버 시각 2026-11-25(수) 12:00 KST, 수요일 12:00
    Map<String, Object> body =
        requestWith(
            "startDate", "2026-11-25",
            "endDate", "2026-12-02",
            "daysOfWeek", List.of("WED"),
            "meetingTime", "12:00");

    // when
    createGroup(body);

    // then
    assertThat(meetingsByTime())
        .extracting(Meeting::getMeetingAt)
        .containsExactly(Instant.parse("2026-12-02T03:00:00Z"));
  }

  @Test
  @DisplayName("기간 안에 만들 회차가 없으면 NO_MEETING_IN_PERIOD로 거절되고 아무것도 저장되지 않는다")
  void create_noMeetingInPeriod_rejectedAndNothingSaved() throws Exception {
    // given: 2026-11-30(월) ~ 2026-12-01(화), 금요일만
    Map<String, Object> body = requestWith("endDate", "2026-12-01", "daysOfWeek", List.of("FRI"));

    // when
    ResultActions result = create(ownerId, body);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NO_MEETING_IN_PERIOD"))
        .andExpect(jsonPath("$.message").value("기간 안에 고른 요일의 약속이 없어요. 기간이나 요일을 바꿔 주세요."));
    assertNothingSaved();
  }

  @Test
  @DisplayName("시작일이 어제(KST)면 MEETING_TIME_IN_PAST로 거절되고 아무것도 저장되지 않는다")
  void create_startYesterday_rejectedAsInPastAndNothingSaved() throws Exception {
    // when
    ResultActions result = create(ownerId, requestWith("startDate", "2026-11-24"));

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MEETING_TIME_IN_PAST"));
    assertNothingSaved();
  }

  private static final String PERIOD = "recurringPeriodValid";
  private static final String DAYS = "recurringDaysPresent";

  /** (케이스 이름, 걸려야 하는 필드, 덮어쓸 값). 필드가 null이면 본문 해석 단계에서 거절되는 경우다. */
  static Stream<Arguments> rejectedValues() {
    return Stream.of(
        Arguments.of("종료일 없음", PERIOD, new Object[] {"endDate", null}),
        Arguments.of("종료일이 시작일 하루 전", PERIOD, new Object[] {"endDate", "2026-11-29"}),
        Arguments.of("기간 91일", PERIOD, new Object[] {"endDate", "2027-02-28"}),
        Arguments.of("요일 없음", DAYS, new Object[] {"daysOfWeek", null}),
        Arguments.of("요일 빈 배열", DAYS, new Object[] {"daysOfWeek", List.of()}),
        Arguments.of("요일에 null", DAYS, new Object[] {"daysOfWeek", Arrays.asList("MON", null)}),
        Arguments.of("정의되지 않은 요일", null, new Object[] {"daysOfWeek", List.of("MONDAY")}),
        Arguments.of("정의되지 않은 그룹 유형", null, new Object[] {"type", "WEEKLY"}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedValues")
  @DisplayName("정기 모임의 기간·요일이 잘못되면 그 필드로 VALIDATION_FAILED가 나고 저장되지 않는다")
  void create_invalidSchedule_rejectedAndNothingSaved(
      String caseName, String expectedField, Object[] overrides) throws Exception {
    // when
    ResultActions result = create(ownerId, requestWith(overrides));

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    if (expectedField != null) {
      result.andExpect(jsonPath("$.message").value(startsWith(expectedField + ": ")));
    }
    assertNothingSaved();
  }

  @Test
  @DisplayName("type을 생략하면 일회성으로 만들고, 보낸 종료일·요일은 무시한다")
  void create_typeOmittedWithPeriod_createsOneTimeIgnoringPeriod() throws Exception {
    // given: 일회성으로 해석되면 startDate 2026-11-30 19:00 KST 회차 1개
    Map<String, Object> body = requestWith("type", null);

    // when
    ResultActions result = create(ownerId, body);

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("ONE_TIME"))
        .andExpect(jsonPath("$.endDate").value(nullValue()))
        .andExpect(jsonPath("$.daysOfWeek").value(nullValue()));
    assertThat(meetingsByTime()).extracting(Meeting::getMeetingAt).containsExactly(FIRST);
    Map<String, Object> row =
        jdbcTemplate.queryForMap("select end_date, days_of_week from meeting_groups");
    assertThat(row.get("end_date")).isNull();
    assertThat(row.get("days_of_week")).isNull();
  }

  // ---- 이후 참여/나가기 ----

  @Test
  @DisplayName("두 번째 회차 시각 정각에 참여하면 시작한 회차는 빼고 남은 회차에만 READY로 등록된다")
  void join_atSecondMeetingTime_registeredOnlyInRemainingMeetings() throws Exception {
    // given
    Long groupId = createGroup(validRequest());
    clock.setInstant(SECOND);
    String code = issueCode(groupId);

    // when
    join(guestId, code);

    // then
    assertThat(attendedMeetingTimes(guestId)).containsExactly(THIRD, FOURTH);
  }

  @Test
  @DisplayName("기간 중간에 나가면 시작 전 회차의 참석 행만 지우고 시작한 회차의 기록은 남는다")
  void leave_atSecondMeetingTime_keepsStartedMeetings() throws Exception {
    // given: 시작 전에 참여해 4회차 모두 등록
    Long groupId = createGroup(validRequest());
    join(guestId, issueCode(groupId));
    assertThat(attendedMeetingTimes(guestId)).containsExactly(FIRST, SECOND, THIRD, FOURTH);
    clock.setInstant(SECOND);

    // when
    mockMvc
        .perform(delete(BASE + "/" + groupId + "/members/me").header(USER_HEADER, guestId.toString()))
        .andExpect(status().isNoContent());

    // then
    assertThat(attendedMeetingTimes(guestId)).containsExactly(FIRST, SECOND);
    assertThat(attendedMeetingTimes(ownerId)).containsExactly(FIRST, SECOND, THIRD, FOURTH);
  }

  // ---- DB 제약 ----

  @Test
  @DisplayName("DB CHECK: 정기 모임의 요일 비트마스크가 0이면 저장되지 않는다")
  void check_recurringWithZeroDays_rejectedByDb() throws Exception {
    // given
    Long groupId = createGroup(validRequest());

    // when & then
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set days_of_week = 0 where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set days_of_week = 128 where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("DB CHECK: 정기 모임의 종료일이 시작일보다 앞서거나 없으면 저장되지 않는다")
  void check_recurringEndBeforeStart_rejectedByDb() throws Exception {
    // given
    Long groupId = createGroup(validRequest());

    // when & then
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set end_date = start_date - 1 where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set end_date = null where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("DB CHECK: 일회성 그룹에 요일이나 종료일이 있으면 저장되지 않는다")
  void check_oneTimeWithSchedule_rejectedByDb() throws Exception {
    // given
    Long groupId = createGroup(requestWith("type", "ONE_TIME"));

    // when & then
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set days_of_week = 1 where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update meeting_groups set end_date = start_date where id = ?", groupId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
