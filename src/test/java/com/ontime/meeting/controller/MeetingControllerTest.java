package com.ontime.meeting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.auth.LoginUserArgumentResolver;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.support.MutableClock;
import com.ontime.support.MutableClockConfig;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 회차 목록, 회차 상세, 그룹 생성/참여/나가기와 참석 행 연동 통합 테스트. 실제 PostgreSQL, 시각은 옮길 수 있는 Clock 빈. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfig.class})
class MeetingControllerTest {

  private static final String GROUPS = "/api/v1/meeting-groups";
  private static final String MEETINGS = "/api/v1/meetings";
  private static final String USER_HEADER = LoginUserArgumentResolver.USER_ID_HEADER;

  // 서버 시각 2026-11-25 12:00 KST. 초대 코드는 2026-12-02T03:00:00Z 에 만료되므로 약속을 그 전으로 잡는다.
  private static final Instant NOW = MutableClockConfig.NOW;

  // 약속 2026-11-28 19:00 KST = 2026-11-28T10:00:00Z
  private static final Instant MEETING_AT = Instant.parse("2026-11-28T10:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MutableClock clock;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private MeetingRepository meetingRepository;
  @Autowired private AttendanceRepository attendanceRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Long ownerId;
  private Long guestId;
  private Long groupId;

  @BeforeEach
  void setUp() throws Exception {
    clock.setInstant(NOW);
    ownerId = userRepository.save(User.create("방장")).getId();
    guestId = userRepository.save(User.create("손님")).getId();
    groupId = createGroup(ownerId);
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

  private Long createGroup(Long userId) throws Exception {
    Map<String, Object> body =
        Map.ofEntries(
            Map.entry("name", "토요일 모임"),
            Map.entry("placeName", "강남역 11번 출구"),
            Map.entry("latitude", 37.4979),
            Map.entry("longitude", 127.0276),
            Map.entry("startDate", "2026-11-28"),
            Map.entry("meetingTime", "19:00"),
            Map.entry("graceMinutes", 5),
            Map.entry("absentAfterMinutes", 60),
            Map.entry("arrivalRadiusM", 200),
            Map.entry("fineType", "MONEY"),
            Map.entry("fineAmount", 3000));
    String json =
        mockMvc
            .perform(
                post(GROUPS)
                    .header(USER_HEADER, userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private String issueCode() throws Exception {
    return issueCode(ownerId, groupId);
  }

  private String issueCode(Long groupOwnerId, Long groupId) throws Exception {
    String json =
        mockMvc
            .perform(
                post(GROUPS + "/" + groupId + "/invite-codes")
                    .header(USER_HEADER, groupOwnerId.toString()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("code").asString();
  }

  private void join(Long userId, String code) throws Exception {
    mockMvc
        .perform(
            post(GROUPS + "/members")
                .header(USER_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("inviteCode", code))))
        .andExpect(status().isCreated());
  }

  private void leave(Long userId) throws Exception {
    mockMvc
        .perform(
            delete(GROUPS + "/" + groupId + "/members/me").header(USER_HEADER, userId.toString()))
        .andExpect(status().isNoContent());
  }

  private ResultActions list(Long userId, Long groupId, String query) throws Exception {
    return mockMvc.perform(
        get(GROUPS + "/" + groupId + "/meetings" + query).header(USER_HEADER, userId.toString()));
  }

  private ResultActions detail(Long userId, Long meetingId) throws Exception {
    return mockMvc.perform(get(MEETINGS + "/" + meetingId).header(USER_HEADER, userId.toString()));
  }

  /** 그룹의 첫 회차 id. 일회성 그룹은 생성할 때 회차가 하나 생긴다. */
  private Long meetingIdOf(Long groupId) {
    return transactionTemplate.execute(
        s ->
            meetingRepository.findAll().stream()
                .filter(m -> m.getGroup().getId().equals(groupId))
                .mapToLong(Meeting::getId)
                .min()
                .orElseThrow());
  }

  /** 회차를 직접 하나 더 만든다. (정기 모임 전이라 API로는 그룹당 하나만 생긴다) */
  private Long addMeeting(Long groupId) {
    return transactionTemplate.execute(
        s ->
            meetingRepository
                .save(Meeting.oneTimeOf(meetingGroupRepository.findById(groupId).orElseThrow()))
                .getId());
  }

  /** 상태 전이(Phase 3 스케줄러) 전이라 API로는 바꿀 수 없어 SQL로 회차 상태를 바꾼다. */
  private void markOngoing(Long meetingId) {
    jdbcTemplate.update("update meetings set status = 'ONGOING' where id = ?", meetingId);
  }

  private long attendanceCountOf(Long userId) {
    return transactionTemplate.execute(
        s ->
            attendanceRepository.findAll().stream()
                .filter(a -> a.getUser().getId().equals(userId))
                .count());
  }

  // ---- 그룹 생성 ----

  @Test
  @DisplayName("그룹을 만들면 회차 1개가 SCHEDULED로 생기고 방장이 READY로 등록된다")
  void createGroup_createsScheduledMeetingWithOwnerReady() throws Exception {
    // when
    ResultActions result = list(ownerId, groupId, "");

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].meetingAt").value(MEETING_AT.toString()))
        .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
        .andExpect(jsonPath("$.items[0].placeName").value("강남역 11번 출구"))
        .andExpect(jsonPath("$.items[0].myStatus").value("READY"));
    assertThat(attendanceCountOf(ownerId)).isEqualTo(1);
  }

  // ---- 회차 상세 ----

  @Test
  @DisplayName("회차 상세는 회차에 복사된 장소/규칙과 멤버별 상태, 내 상태를 준다")
  void detail_member_returnsSnapshotAndAttendances() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);

    // when
    ResultActions result = detail(ownerId, meetingId);

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(meetingId))
        .andExpect(jsonPath("$.groupId").value(groupId))
        .andExpect(jsonPath("$.groupName").value("토요일 모임"))
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.meetingAt").value(MEETING_AT.toString()))
        .andExpect(jsonPath("$.placeName").value("강남역 11번 출구"))
        .andExpect(jsonPath("$.latitude").value(37.4979))
        .andExpect(jsonPath("$.longitude").value(127.0276))
        .andExpect(jsonPath("$.graceMinutes").value(5))
        .andExpect(jsonPath("$.absentAfterMinutes").value(60))
        .andExpect(jsonPath("$.arrivalRadiusM").value(200))
        .andExpect(jsonPath("$.fineType").value("MONEY"))
        .andExpect(jsonPath("$.fineAmount").value(3000))
        .andExpect(jsonPath("$.penaltyText").value(nullValue()))
        .andExpect(jsonPath("$.myStatus").value("READY"))
        .andExpect(jsonPath("$.attendances", hasSize(1)))
        .andExpect(jsonPath("$.attendances[0].userId").value(ownerId))
        .andExpect(jsonPath("$.attendances[0].nickname").value("방장"))
        .andExpect(jsonPath("$.attendances[0].status").value("READY"))
        .andExpect(jsonPath("$.attendances[0].departedAt").value(nullValue()))
        .andExpect(jsonPath("$.attendances[0].arrivedAt").value(nullValue()));
  }

  @Test
  @DisplayName("그룹 멤버가 아니면 회차 상세가 403 NOT_GROUP_MEMBER다")
  void detail_nonMember_forbidden() throws Exception {
    // when
    ResultActions result = detail(guestId, meetingIdOf(groupId));

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("없는 회차를 조회하면 404 MEETING_NOT_FOUND다")
  void detail_missingMeeting_notFound() throws Exception {
    // when
    ResultActions result = detail(ownerId, meetingIdOf(groupId) + 1000);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("약속을 찾을 수 없어요."));
  }

  @Test
  @DisplayName("삭제된 그룹의 회차를 조회하면 404 MEETING_NOT_FOUND다")
  void detail_deletedGroup_notFound() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    mockMvc
        .perform(delete(GROUPS + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = detail(ownerId, meetingId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
  }

  @Test
  @DisplayName("다른 회차에 참석 행이 있어도 상세에는 이 회차의 참석자만 나온다")
  void detail_otherMeetingHasAttendees_returnsOnlyThisMeetingAttendees() throws Exception {
    // given: 손님의 그룹 회차에 손님과 셋째가 참석
    Long guestGroupId = createGroup(guestId);
    Long thirdId = userRepository.save(User.create("셋째")).getId();
    join(thirdId, issueCode(guestId, guestGroupId));

    // when
    ResultActions result = detail(ownerId, meetingIdOf(groupId));

    // then
    result
        .andExpect(jsonPath("$.attendances", hasSize(1)))
        .andExpect(jsonPath("$.attendances[0].userId").value(ownerId));
  }

  @Test
  @DisplayName("참석자는 사용자 id가 아니라 회차에 등록된 순서로 나온다")
  void detail_attendances_orderedByRegistrationNotUserId() throws Exception {
    // given: 손님(나중에 만든 사용자)의 그룹에 방장(먼저 만든 사용자)이 참여
    Long guestGroupId = createGroup(guestId);
    join(ownerId, issueCode(guestId, guestGroupId));
    // 먼저 등록된 행을 한 번 UPDATE 해 저장 위치를 뒤로 민다. order by 가 없으면 물리 순서(방장, 손님)로 나온다.
    jdbcTemplate.update("update attendances set status = status where user_id = ?", guestId);

    // when
    ResultActions result = detail(guestId, meetingIdOf(guestGroupId));

    // then
    result
        .andExpect(jsonPath("$.attendances", hasSize(2)))
        .andExpect(jsonPath("$.attendances[0].userId").value(guestId))
        .andExpect(jsonPath("$.attendances[1].userId").value(ownerId));
  }

  @Test
  @DisplayName("회차를 만든 뒤 그룹 행의 장소/규칙이 바뀌어도 상세는 회차에 복사된 값을 준다")
  void detail_groupRulesChangedAfterCreation_returnsMeetingSnapshot() throws Exception {
    // given: 그룹 규칙 수정 API는 Phase 5라 SQL로 바꾼다
    Long meetingId = meetingIdOf(groupId);
    int updated =
        jdbcTemplate.update(
            """
            update meeting_groups
            set place_name = '바뀐 장소', latitude = 35.0, longitude = 129.0,
                grace_minutes = 10, absent_after_minutes = 90, arrival_radius_m = 500,
                fine_type = 'TEXT', fine_amount = 0, penalty_text = '커피 사기'
            where id = ?
            """,
            groupId);
    assertThat(updated).isEqualTo(1);

    // when
    ResultActions result = detail(ownerId, meetingId);

    // then
    result
        .andExpect(jsonPath("$.placeName").value("강남역 11번 출구"))
        .andExpect(jsonPath("$.latitude").value(37.4979))
        .andExpect(jsonPath("$.longitude").value(127.0276))
        .andExpect(jsonPath("$.graceMinutes").value(5))
        .andExpect(jsonPath("$.absentAfterMinutes").value(60))
        .andExpect(jsonPath("$.arrivalRadiusM").value(200))
        .andExpect(jsonPath("$.fineType").value("MONEY"))
        .andExpect(jsonPath("$.fineAmount").value(3000))
        .andExpect(jsonPath("$.penaltyText").value(nullValue()));
  }

  @Test
  @DisplayName("회차 상세의 쿼리 수는 참석 인원과 관계없이 같다 (N+1 없음)")
  void detail_queryCount_doesNotGrowWithAttendees() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    try {
      long withOne = queryCountOfDetail(statistics, meetingId);
      String code = issueCode();
      for (int i = 0; i < 3; i++) {
        join(userRepository.save(User.create("멤버" + i)).getId(), code);
      }

      // when
      long withFour = queryCountOfDetail(statistics, meetingId);

      // then
      assertThat(withFour).isEqualTo(withOne);
    } finally {
      statistics.setStatisticsEnabled(false);
    }
  }

  private long queryCountOfDetail(Statistics statistics, Long meetingId) throws Exception {
    statistics.clear();
    detail(ownerId, meetingId).andExpect(status().isOk());
    return statistics.getPrepareStatementCount();
  }

  // ---- 참여와 참석 행 ----

  @Test
  @DisplayName("약속 1초 전에 참여하면 READY로 등록되고 상세에 참여 순으로 보인다")
  void join_oneSecondBeforeMeeting_addsReadyAttendance() throws Exception {
    // given
    String code = issueCode();
    clock.setInstant(MEETING_AT.minusSeconds(1));

    // when
    join(guestId, code);

    // then
    detail(guestId, meetingIdOf(groupId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myStatus").value("READY"))
        .andExpect(jsonPath("$.attendances", hasSize(2)))
        .andExpect(jsonPath("$.attendances[0].userId").value(ownerId))
        .andExpect(jsonPath("$.attendances[1].userId").value(guestId))
        .andExpect(jsonPath("$.attendances[1].nickname").value("손님"))
        .andExpect(jsonPath("$.attendances[1].status").value("READY"));
  }

  @Test
  @DisplayName("약속 시각 정각에 참여하면 이미 시작한 회차라 참석 행이 생기지 않고 내 상태는 null이다")
  void join_atMeetingTime_noAttendance() throws Exception {
    // given
    String code = issueCode();
    clock.setInstant(MEETING_AT);

    // when
    join(guestId, code);

    // then
    detail(guestId, meetingIdOf(groupId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myStatus").value(nullValue()))
        .andExpect(jsonPath("$.attendances", hasSize(1)))
        .andExpect(jsonPath("$.attendances[0].userId").value(ownerId));
    list(guestId, groupId, "").andExpect(jsonPath("$.items[0].myStatus").value(nullValue()));
    assertThat(attendanceCountOf(guestId)).isZero();
  }

  @Test
  @DisplayName("약속 시각 전이라도 SCHEDULED가 아닌(ONGOING) 회차에는 참여해도 참석 행이 생기지 않는다")
  void join_ongoingMeetingBeforeMeetingTime_noAttendance() throws Exception {
    // given
    String code = issueCode();
    markOngoing(meetingIdOf(groupId));

    // when
    join(guestId, code);

    // then
    assertThat(attendanceCountOf(guestId)).isZero();
  }

  // ---- 나가기와 참석 행 ----

  @Test
  @DisplayName("약속 시각 전이라도 SCHEDULED가 아닌(ONGOING) 회차의 참석 기록은 나가도 남는다")
  void leave_ongoingMeetingBeforeMeetingTime_keepsAttendance() throws Exception {
    // given
    join(guestId, issueCode());
    markOngoing(meetingIdOf(groupId));

    // when
    leave(guestId);

    // then
    assertThat(attendanceCountOf(guestId)).isEqualTo(1);
  }

  @Test
  @DisplayName("방장의 나가기가 OWNER_CANNOT_LEAVE로 거절되면 방장의 참석 행도 그대로다")
  void leave_ownerRejected_keepsOwnerAttendance() throws Exception {
    // when
    ResultActions result =
        mockMvc.perform(
            delete(GROUPS + "/" + groupId + "/members/me")
                .header(USER_HEADER, ownerId.toString()));

    // then
    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));
    assertThat(attendanceCountOf(ownerId)).isEqualTo(1);
  }

  @Test
  @DisplayName("약속 1초 전에 나가면 그 회차의 참석 행이 지워져 상세에서 사라진다")
  void leave_oneSecondBeforeMeeting_removesAttendance() throws Exception {
    // given
    join(guestId, issueCode());
    clock.setInstant(MEETING_AT.minusSeconds(1));

    // when
    leave(guestId);

    // then
    assertThat(attendanceCountOf(guestId)).isZero();
    detail(ownerId, meetingIdOf(groupId))
        .andExpect(jsonPath("$.attendances", hasSize(1)))
        .andExpect(jsonPath("$.attendances[0].userId").value(ownerId));
  }

  @Test
  @DisplayName("약속 시각 정각에 나가면 이미 시작한 회차라 참석 기록이 남는다")
  void leave_atMeetingTime_keepsAttendance() throws Exception {
    // given
    join(guestId, issueCode());
    clock.setInstant(MEETING_AT);

    // when
    leave(guestId);

    // then
    assertThat(attendanceCountOf(guestId)).isEqualTo(1);
    detail(ownerId, meetingIdOf(groupId))
        .andExpect(jsonPath("$.attendances", hasSize(2)))
        .andExpect(jsonPath("$.attendances[1].userId").value(guestId))
        .andExpect(jsonPath("$.attendances[1].status").value("READY"));
  }

  @Test
  @DisplayName("나간 멤버는 참석 기록이 남아 있어도 회차 상세를 볼 수 없다")
  void detail_leftMemberWithKeptAttendance_forbidden() throws Exception {
    // given: 약속 시각에 나가서 참석 행이 남은 상태
    join(guestId, issueCode());
    clock.setInstant(MEETING_AT);
    leave(guestId);

    // when
    ResultActions result = detail(guestId, meetingIdOf(groupId));

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("약속 전에 나갔다가 다시 참여하면 참석 행이 하나만 READY로 다시 생긴다")
  void rejoin_afterLeavingBeforeMeeting_readyOnce() throws Exception {
    // given
    String code = issueCode();
    join(guestId, code);
    leave(guestId);

    // when
    join(guestId, code);

    // then
    assertThat(attendanceCountOf(guestId)).isEqualTo(1);
    detail(guestId, meetingIdOf(groupId)).andExpect(jsonPath("$.myStatus").value("READY"));
  }

  @Test
  @DisplayName("나가면 그 그룹의 회차 참석 행만 지워지고 다른 그룹의 참석 행은 남는다")
  void leave_otherGroupAttendance_kept() throws Exception {
    // given: 손님이 자기 그룹(방장)도 가지고 있고, 이 그룹에도 참여
    Long guestGroupId = createGroup(guestId);
    join(guestId, issueCode());

    // when
    leave(guestId);

    // then
    assertThat(attendanceCountOf(guestId)).isEqualTo(1);
    detail(guestId, meetingIdOf(guestGroupId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myStatus").value("READY"));
  }

  // ---- 회차 목록 ----

  @Test
  @DisplayName("회차 목록은 id 오름차순 커서 페이지네이션이고 다른 그룹의 회차는 섞이지 않는다")
  void list_paginatesByIdAscWithinGroup() throws Exception {
    // given: 이 그룹에 회차 3개, 다른 그룹에 회차 1개
    Long first = meetingIdOf(groupId);
    createGroup(guestId);
    Long second = addMeeting(groupId);
    Long third = addMeeting(groupId);

    // when
    ResultActions page1 = list(ownerId, groupId, "?size=2");

    // then
    page1
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(2)))
        .andExpect(jsonPath("$.items[0].id").value(first))
        .andExpect(jsonPath("$.items[1].id").value(second))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(second));
    list(ownerId, groupId, "?size=2&cursor=" + second)
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].id").value(third))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  @DisplayName("참석 행이 없는 회차는 목록의 내 상태가 null이다")
  void list_meetingWithoutMyAttendance_myStatusNull() throws Exception {
    // given: 직접 추가한 회차에는 참석 행이 없다
    Long added = addMeeting(groupId);

    // when
    ResultActions result = list(ownerId, groupId, "");

    // then
    result
        .andExpect(jsonPath("$.items", hasSize(2)))
        .andExpect(jsonPath("$.items[0].myStatus").value("READY"))
        .andExpect(jsonPath("$.items[1].id").value(added))
        .andExpect(jsonPath("$.items[1].myStatus").value(nullValue()));
  }

  @Test
  @DisplayName("그룹 멤버가 아니면 회차 목록이 403 NOT_GROUP_MEMBER다")
  void list_nonMember_forbidden() throws Exception {
    // when
    ResultActions result = list(guestId, groupId, "");

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("삭제된 그룹의 회차 목록은 404 GROUP_NOT_FOUND다")
  void list_deletedGroup_notFound() throws Exception {
    // given
    mockMvc
        .perform(delete(GROUPS + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = list(ownerId, groupId, "");

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  @ParameterizedTest(name = "size={0}")
  @ValueSource(ints = {1, 50})
  @DisplayName("회차 목록 size 1과 50은 허용된다")
  void list_sizeAtBounds_accepted(int size) throws Exception {
    // when & then
    list(ownerId, groupId, "?size=" + size)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)));
  }

  @ParameterizedTest(name = "size={0}")
  @ValueSource(ints = {0, 51})
  @DisplayName("회차 목록 size가 1~50 밖이면 400 VALIDATION_FAILED다")
  void list_sizeOutOfRange_rejected(int size) throws Exception {
    // when & then
    list(ownerId, groupId, "?size=" + size)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ---- DB 제약 ----

  @Test
  @DisplayName("같은 회차에 같은 사용자의 참석 행을 또 넣으면 (meeting_id, user_id) 유니크 제약이 막는다")
  void attendance_duplicateMeetingUser_rejectedByUniqueConstraint() {
    // given: 방장은 그룹 생성 때 이미 참석 행이 있다
    Long meetingId = meetingIdOf(groupId);

    // when & then
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    s ->
                        attendanceRepository.saveAndFlush(
                            Attendance.ready(
                                meetingRepository.getReferenceById(meetingId),
                                userRepository.getReferenceById(ownerId)))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            e -> assertThat(violatedConstraint(e)).isEqualTo(Attendance.UNIQUE_MEETING_USER));
    assertThat(attendanceCountOf(ownerId)).isEqualTo(1);
  }

  private static String violatedConstraint(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException cve) {
        return cve.getConstraintName();
      }
    }
    return null;
  }

  // ---- 출발 ----

  // 약속 10:00Z, 유예 5분 → 버튼은 07:00:00Z 부터 열리고 10:05:00Z 정각부터 지각이다.
  private static final Instant OPENS_AT = Instant.parse("2026-11-28T07:00:00Z");
  private static final Instant LATE_FROM = Instant.parse("2026-11-28T10:05:00Z");

  private ResultActions depart(Long userId, Long meetingId) throws Exception {
    return mockMvc.perform(
        post(MEETINGS + "/" + meetingId + "/depart").header(USER_HEADER, userId.toString()));
  }

  /** DB에 저장된 참석 행의 상태와 출발 시각. API 응답이 아니라 실제 커밋 결과를 본다. */
  private Map<String, Object> attendanceRow(Long meetingId, Long userId) {
    return jdbcTemplate.queryForMap(
        "select status, departed_at, version from attendances where meeting_id = ? and user_id = ?",
        meetingId,
        userId);
  }

  private void assertStillReady(Long meetingId, Long userId) {
    Map<String, Object> row = attendanceRow(meetingId, userId);
    assertThat(row.get("status")).isEqualTo("READY");
    assertThat(row.get("departed_at")).isNull();
    assertThat(row.get("version")).isEqualTo(0L);
  }

  @Test
  @DisplayName("버튼이 열린 시간에 출발하면 200과 내 참석 상태를 주고, DB에 MOVING과 서버 시각이 저장된다")
  void depart_openWindow_persistsMovingWithServerTime() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    join(guestId, issueCode());
    Instant now = Instant.parse("2026-11-28T09:30:00Z");
    clock.setInstant(now);

    // when
    ResultActions result = depart(ownerId, meetingId);

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(ownerId))
        .andExpect(jsonPath("$.nickname").value("방장"))
        .andExpect(jsonPath("$.status").value("MOVING"))
        .andExpect(jsonPath("$.departedAt").value("2026-11-28T09:30:00Z"))
        .andExpect(jsonPath("$.arrivedAt").value(nullValue()));
    Map<String, Object> row = attendanceRow(meetingId, ownerId);
    assertThat(row.get("status")).isEqualTo("MOVING");
    assertThat(((Timestamp) row.get("departed_at")).toInstant()).isEqualTo(now);
    assertThat(row.get("version")).isEqualTo(1L);
    // 다른 멤버의 참석 행은 바뀌지 않는다
    assertStillReady(meetingId, guestId);
    detail(guestId, meetingId)
        .andExpect(jsonPath("$.attendances[0].status").value("MOVING"))
        .andExpect(jsonPath("$.attendances[1].status").value("READY"));
  }

  @Test
  @DisplayName("약속 3시간 전 정각에는 출발할 수 있다")
  void depart_exactlyAtOpen_ok() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(OPENS_AT);

    // when & then
    depart(ownerId, meetingId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.departedAt").value("2026-11-28T07:00:00Z"));
    assertThat(attendanceRow(meetingId, ownerId).get("status")).isEqualTo("MOVING");
  }

  @Test
  @DisplayName("약속 3시간 전 1초 전에 출발하면 409 MEETING_NOT_OPEN이고 참석 행은 그대로다")
  void depart_oneSecondBeforeOpen_conflictMeetingNotOpen() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(OPENS_AT.minusSeconds(1));

    // when & then
    depart(ownerId, meetingId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MEETING_NOT_OPEN"))
        .andExpect(jsonPath("$.message").value("아직 출발/도착을 누를 수 없는 시간이에요."));
    assertStillReady(meetingId, ownerId);
  }

  @Test
  @DisplayName("약속시간 + 유예 정각에 출발하면 READY여도 409 INVALID_STATE이고 참석 행은 그대로다")
  void depart_atGraceEnd_conflictInvalidState() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(LATE_FROM);

    // when & then
    depart(ownerId, meetingId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"))
        .andExpect(jsonPath("$.message").value("지금은 할 수 없는 동작이에요."));
    assertStillReady(meetingId, ownerId);
  }

  @Test
  @DisplayName("유예 끝나기 1초 전에는 출발할 수 있다")
  void depart_oneSecondBeforeGraceEnd_ok() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(LATE_FROM.minusSeconds(1));

    // when & then
    depart(ownerId, meetingId).andExpect(status().isOk());
    assertThat(attendanceRow(meetingId, ownerId).get("status")).isEqualTo("MOVING");
  }

  @Test
  @DisplayName("이미 출발했는데 다시 출발하면 409 INVALID_STATE이고 처음 출발 시각이 그대로다")
  void depart_twice_conflictInvalidStateAndKeepsFirstDepartedAt() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(Instant.parse("2026-11-28T09:00:00Z"));
    depart(ownerId, meetingId).andExpect(status().isOk());
    clock.setInstant(Instant.parse("2026-11-28T09:10:00Z"));

    // when & then
    depart(ownerId, meetingId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    Map<String, Object> row = attendanceRow(meetingId, ownerId);
    assertThat(row.get("status")).isEqualTo("MOVING");
    assertThat(((Timestamp) row.get("departed_at")).toInstant())
        .isEqualTo(Instant.parse("2026-11-28T09:00:00Z"));
    assertThat(row.get("version")).isEqualTo(1L);
  }

  @Test
  @DisplayName("그룹 멤버가 아니면 출발이 403 NOT_GROUP_MEMBER다")
  void depart_nonMember_forbidden() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    clock.setInstant(Instant.parse("2026-11-28T09:30:00Z"));

    // when & then
    depart(guestId, meetingId)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertStillReady(meetingId, ownerId);
  }

  @Test
  @DisplayName("없는 회차에 출발하면 404 MEETING_NOT_FOUND다")
  void depart_missingMeeting_notFound() throws Exception {
    // given
    clock.setInstant(Instant.parse("2026-11-28T09:30:00Z"));

    // when & then
    depart(ownerId, meetingIdOf(groupId) + 1000)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
  }

  @Test
  @DisplayName("삭제된 그룹의 회차에 출발하면 404 MEETING_NOT_FOUND이고 참석 행은 그대로다")
  void depart_deletedGroup_notFound() throws Exception {
    // given
    Long meetingId = meetingIdOf(groupId);
    mockMvc
        .perform(delete(GROUPS + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());
    clock.setInstant(Instant.parse("2026-11-28T09:30:00Z"));

    // when & then
    depart(ownerId, meetingId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
    assertStillReady(meetingId, ownerId);
  }

  @Test
  @DisplayName("회차가 시작한 뒤 참여해 참석 행이 없는 멤버가 출발하면 409 INVALID_STATE이고 참석 행이 생기지 않는다")
  void depart_joinedAfterStart_conflictInvalidState() throws Exception {
    // given: 약속 정각에 참여해 참석 행이 없다. 출발 시각은 유예 안이라 시간 때문에 거절되지 않는다
    Long meetingId = meetingIdOf(groupId);
    String code = issueCode();
    clock.setInstant(MEETING_AT);
    join(guestId, code);
    clock.setInstant(MEETING_AT.plusSeconds(60));

    // when & then
    depart(guestId, meetingId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(attendanceCountOf(guestId)).isZero();
  }

  @Test
  @DisplayName("나간 멤버는 참석 기록이 남아 있어도 출발할 수 없다 (403 NOT_GROUP_MEMBER)")
  void depart_leftMemberWithKeptAttendance_forbidden() throws Exception {
    // given: 약속 정각에 나가서 참석 행이 남은 상태. 출발 시각은 유예 안이다
    Long meetingId = meetingIdOf(groupId);
    join(guestId, issueCode());
    clock.setInstant(MEETING_AT);
    leave(guestId);
    clock.setInstant(MEETING_AT.plusSeconds(60));

    // when & then
    depart(guestId, meetingId)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertStillReady(meetingId, guestId);
  }
}
