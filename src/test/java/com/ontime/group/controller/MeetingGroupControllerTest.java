package com.ontime.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.auth.LoginUserArgumentResolver;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.GroupRole;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 그룹 CRUD API 통합 테스트. 실제 PostgreSQL에 저장하고, 서버 시각은 고정 Clock 빈으로 정한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MeetingGroupControllerTest.FixedClockConfig.class})
class MeetingGroupControllerTest {

  private static final String BASE = "/api/v1/meeting-groups";
  private static final String USER_HEADER = LoginUserArgumentResolver.USER_ID_HEADER;

  // 서버 시각 2026-11-25 12:00 KST
  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private Long ownerId;
  private Long otherId;

  @BeforeEach
  void setUp() {
    ownerId = userRepository.save(User.create("방장")).getId();
    otherId = userRepository.save(User.create("남")).getId();
  }

  @AfterEach
  void tearDown() {
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  // ---- 헬퍼 ----

  /** 정상 생성 요청. 2026-12-04 19:00 KST, 유예 5분, 결석 60분, 반경 200m, 벌금 3000원. */
  private static Map<String, Object> validRequest() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "금요일 모임");
    body.put("placeName", "강남역 11번 출구");
    body.put("latitude", 37.4979);
    body.put("longitude", 127.0276);
    body.put("startDate", "2026-12-04");
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

  private Long createGroup(Long userId) throws Exception {
    String json =
        create(userId, validRequest())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private ResultActions list(Long userId, String query) throws Exception {
    return mockMvc.perform(get(BASE + query).header(USER_HEADER, userId.toString()));
  }

  private ResultActions getDetail(Long userId, Long groupId) throws Exception {
    return mockMvc.perform(get(BASE + "/" + groupId).header(USER_HEADER, userId.toString()));
  }

  private ResultActions rename(Long userId, Long groupId, String name) throws Exception {
    return mockMvc.perform(
        patch(BASE + "/" + groupId)
            .header(USER_HEADER, userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", name))));
  }

  private ResultActions deleteGroup(Long userId, Long groupId) throws Exception {
    return mockMvc.perform(delete(BASE + "/" + groupId).header(USER_HEADER, userId.toString()));
  }

  /** 초대 코드 흐름 없이 사용자를 일반 멤버로 바로 넣는다. (참여 API는 GroupMemberControllerTest 에서 검증) */
  private void addPlainMember(Long groupId, Long userId) {
    transactionTemplate.executeWithoutResult(
        s ->
            groupMemberRepository.save(
                GroupMember.member(
                    meetingGroupRepository.getReferenceById(groupId),
                    userRepository.getReferenceById(userId))));
  }

  private MeetingGroup findGroup(Long groupId) {
    return meetingGroupRepository.findById(groupId).orElseThrow();
  }

  // ---- 생성 ----

  @Test
  @DisplayName("그룹을 만들면 201, Location, 상세를 돌려주고 요청자가 방장으로 저장된다")
  void create_validRequest_savesGroupAndOwner() throws Exception {
    // when
    ResultActions result = create(ownerId, validRequest());

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("금요일 모임"))
        .andExpect(jsonPath("$.type").value("ONE_TIME"))
        .andExpect(jsonPath("$.myRole").value("OWNER"));
    Long groupId =
        objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
            .get("id")
            .asLong();
    result.andExpect(header().string("Location", BASE + "/" + groupId));

    List<GroupMember> members = groupMemberRepository.findAll();
    assertThat(members).hasSize(1);
    assertThat(members.get(0).getRole()).isEqualTo(GroupRole.OWNER);
    Long memberUserId =
        transactionTemplate.execute(s -> groupMemberRepository.findAll().get(0).getUser().getId());
    assertThat(memberUserId).isEqualTo(ownerId);
  }

  @Test
  @DisplayName("벌칙(TEXT) 그룹은 금액을 보내도 0원으로 저장되고 벌칙 문구가 저장된다")
  void create_textFineWithAmount_savesZeroAmount() throws Exception {
    // given
    Map<String, Object> body =
        requestWith("fineType", "TEXT", "fineAmount", 5000, "penaltyText", "커피 사기");

    // when
    Long groupId =
        objectMapper
            .readTree(
                create(ownerId, body)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asLong();

    // then: 저장된 값을 다시 읽어 확인
    MeetingGroup saved = findGroup(groupId);
    assertThat(saved.getFineRule().getFineAmount()).isZero();
    assertThat(saved.getFineRule().getPenaltyText()).isEqualTo("커피 사기");
  }

  @Test
  @DisplayName("벌칙(TEXT) 그룹은 금액을 0으로 보내도 만들어진다")
  void create_textFineWithZeroAmount_created() throws Exception {
    // given
    Map<String, Object> body =
        requestWith("fineType", "TEXT", "fineAmount", 0, "penaltyText", "커피 사기");

    // when & then
    create(ownerId, body).andExpect(status().isCreated());
    assertThat(meetingGroupRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("약속 일시가 서버 시각과 같으면 MEETING_TIME_IN_PAST로 거절되고 아무것도 저장되지 않는다")
  void create_meetingAtServerTime_rejectedAndNothingSaved() throws Exception {
    // given: 서버 시각 2026-11-25 12:00 KST
    Map<String, Object> body = requestWith("startDate", "2026-11-25", "meetingTime", "12:00");

    // when
    ResultActions result = create(ownerId, body);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MEETING_TIME_IN_PAST"))
        .andExpect(jsonPath("$.message").value("약속 시간은 지금 이후로 정해 주세요."));
    assertThat(meetingGroupRepository.count()).isZero();
    assertThat(groupMemberRepository.count()).isZero();
  }

  @Test
  @DisplayName("약속 일시가 서버 시각 1분 뒤면 만들어진다")
  void create_meetingOneMinuteAfterServerTime_created() throws Exception {
    // given
    Map<String, Object> body = requestWith("startDate", "2026-11-25", "meetingTime", "12:01");

    // when & then
    create(ownerId, body).andExpect(status().isCreated());
    assertThat(meetingGroupRepository.count()).isEqualTo(1);
  }

  static Stream<Arguments> acceptedBoundaries() {
    return Stream.of(
        Arguments.of("이름 30자", new Object[] {"name", "가".repeat(30)}),
        Arguments.of("장소 이름 50자", new Object[] {"placeName", "가".repeat(50)}),
        Arguments.of("벌금 1원", new Object[] {"fineAmount", 1}),
        Arguments.of("벌금 1,000,000원", new Object[] {"fineAmount", 1_000_000}),
        Arguments.of("반경 50m", new Object[] {"arrivalRadiusM", 50}),
        Arguments.of("반경 1000m", new Object[] {"arrivalRadiusM", 1000}),
        Arguments.of("유예 0분", new Object[] {"graceMinutes", 0}),
        Arguments.of("유예 60분, 결석 61분", new Object[] {"graceMinutes", 60, "absentAfterMinutes", 61}),
        Arguments.of("유예 10분, 결석 11분", new Object[] {"graceMinutes", 10, "absentAfterMinutes", 11}),
        Arguments.of("결석 180분", new Object[] {"absentAfterMinutes", 180}),
        Arguments.of("위도 90, 경도 180", new Object[] {"latitude", 90.0, "longitude", 180.0}),
        Arguments.of("위도 -90, 경도 -180", new Object[] {"latitude", -90.0, "longitude", -180.0}),
        Arguments.of(
            "벌칙 문구 100자", new Object[] {"fineType", "TEXT", "penaltyText", "가".repeat(100)}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedBoundaries")
  @DisplayName("허용 범위의 경계값이면 만들어진다")
  void create_valueAtBoundary_created(String caseName, Object[] overrides) throws Exception {
    // when
    ResultActions result = create(ownerId, requestWith(overrides));

    // then
    result.andExpect(status().isCreated());
    assertThat(meetingGroupRepository.count()).isEqualTo(1);
  }

  /** (케이스 이름, 걸려야 하는 필드, 덮어쓸 값). 필드가 null이면 본문 해석 단계에서 거절되는 경우다. */
  static Stream<Arguments> rejectedValues() {
    return Stream.of(
        Arguments.of("이름 31자", "name", new Object[] {"name", "가".repeat(31)}),
        Arguments.of("이름 공백", "name", new Object[] {"name", "  "}),
        Arguments.of("장소 이름 51자", "placeName", new Object[] {"placeName", "가".repeat(51)}),
        Arguments.of("위도 90.1", "latitude", new Object[] {"latitude", 90.1}),
        Arguments.of("위도 -90.1", "latitude", new Object[] {"latitude", -90.1}),
        Arguments.of("경도 180.1", "longitude", new Object[] {"longitude", 180.1}),
        Arguments.of("경도 -180.1", "longitude", new Object[] {"longitude", -180.1}),
        Arguments.of("벌금 0원", FINE_RULE, new Object[] {"fineAmount", 0}),
        Arguments.of("벌금 1,000,001원", FINE_RULE, new Object[] {"fineAmount", 1_000_001}),
        Arguments.of("MONEY인데 벌금 없음", FINE_RULE, new Object[] {"fineAmount", null}),
        Arguments.of("반경 49m", "arrivalRadiusM", new Object[] {"arrivalRadiusM", 49}),
        Arguments.of("반경 1001m", "arrivalRadiusM", new Object[] {"arrivalRadiusM", 1001}),
        Arguments.of("유예 -1분", "graceMinutes", new Object[] {"graceMinutes", -1}),
        Arguments.of(
            "유예 61분", "graceMinutes", new Object[] {"graceMinutes", 61, "absentAfterMinutes", 120}),
        Arguments.of("결석 181분", "absentAfterMinutes", new Object[] {"absentAfterMinutes", 181}),
        Arguments.of(
            "결석 = 유예", ABSENT_RULE, new Object[] {"graceMinutes", 10, "absentAfterMinutes", 10}),
        Arguments.of(
            "결석 < 유예", ABSENT_RULE, new Object[] {"graceMinutes", 10, "absentAfterMinutes", 9}),
        Arguments.of("TEXT인데 벌칙 문구 없음", FINE_RULE, new Object[] {"fineType", "TEXT"}),
        Arguments.of(
            "TEXT인데 벌칙 문구 공백", FINE_RULE, new Object[] {"fineType", "TEXT", "penaltyText", "  "}),
        Arguments.of(
            "벌칙 문구 101자",
            FINE_RULE,
            new Object[] {"fineType", "TEXT", "penaltyText", "가".repeat(101)}),
        Arguments.of("약속 날짜 없음", "startDate", new Object[] {"startDate", null}),
        Arguments.of("정의되지 않은 벌금 유형", null, new Object[] {"fineType", "COFFEE"}));
  }

  private static final String FINE_RULE = "fineRuleComplete";
  private static final String ABSENT_RULE = "absentAfterLongerThanGrace";

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedValues")
  @DisplayName("허용 범위 밖이거나 빠진 값이면 그 필드로 VALIDATION_FAILED가 나고 저장되지 않는다")
  void create_invalidValue_rejectedAndNothingSaved(
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
    assertThat(meetingGroupRepository.count()).isZero();
    assertThat(groupMemberRepository.count()).isZero();
  }

  @Test
  @DisplayName("MONEY 그룹은 벌칙 문구가 101자여도 무시하고 만들어진다")
  void create_moneyWithLongPenaltyText_createdWithoutText() throws Exception {
    // given
    Map<String, Object> body = requestWith("penaltyText", "가".repeat(101));

    // when
    String json =
        create(ownerId, body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // then
    Long groupId = objectMapper.readTree(json).get("id").asLong();
    assertThat(findGroup(groupId).getFineRule().getPenaltyText()).isNull();
  }

  // ---- 목록 ----

  @Test
  @DisplayName("목록은 내가 속한, 삭제되지 않은 그룹만 id 내림차순으로 커서 페이지마다 나눠 준다")
  void list_withDeletedAndOthersGroups_paginatesOwnByIdDesc() throws Exception {
    // given: 내 그룹 3개 + 삭제한 내 그룹 1개 + 남의 그룹 1개
    Long first = createGroup(ownerId);
    Long second = createGroup(ownerId);
    Long deleted = createGroup(ownerId);
    deleteGroup(ownerId, deleted).andExpect(status().isNoContent());
    createGroup(otherId);
    Long third = createGroup(ownerId);

    // when: 첫 페이지
    ResultActions firstPage = list(ownerId, "?size=2");

    // then
    firstPage
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(2)))
        .andExpect(jsonPath("$.items[0].id").value(third))
        .andExpect(jsonPath("$.items[1].id").value(second))
        .andExpect(jsonPath("$.items[0].myRole").value("OWNER"))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(second));

    // when: 다음 페이지 (커서 이후만)
    ResultActions secondPage = list(ownerId, "?size=2&cursor=" + second);

    // then
    secondPage
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].id").value(first))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  @DisplayName("size를 생략하면 기본 20개씩 준다")
  void list_sizeOmitted_usesDefaultTwenty() throws Exception {
    // given: 21개
    for (int i = 0; i < 21; i++) {
      createGroup(ownerId);
    }

    // when
    ResultActions result = list(ownerId, "");

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(20)))
        .andExpect(jsonPath("$.hasNext").value(true));
  }

  @Test
  @DisplayName("size가 최소값 1이면 1개만 준다")
  void list_sizeAtMin_returnsOneItem() throws Exception {
    // given
    createGroup(ownerId);
    Long latest = createGroup(ownerId);

    // when
    ResultActions result = list(ownerId, "?size=1");

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].id").value(latest))
        .andExpect(jsonPath("$.hasNext").value(true));
  }

  @Test
  @DisplayName("size가 최대값 50이면 조회된다")
  void list_sizeAtMax_ok() throws Exception {
    // given
    createGroup(ownerId);

    // when
    ResultActions result = list(ownerId, "?size=50");

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidListQueries")
  @DisplayName("size가 범위 밖이거나 cursor가 숫자가 아니면 VALIDATION_FAILED로 거절된다")
  void list_invalidQuery_rejected(String query) throws Exception {
    // when
    ResultActions result = list(ownerId, query);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  static Stream<String> invalidListQueries() {
    return Stream.of("?size=0", "?size=51", "?cursor=abc");
  }

  // ---- 상세 ----

  @Test
  @DisplayName("멤버가 상세를 조회하면 DB에 저장된 모든 규칙과 내 역할을 돌려준다")
  void get_member_returnsPersistedDetail() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = getDetail(ownerId, groupId);

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(groupId))
        .andExpect(jsonPath("$.name").value("금요일 모임"))
        .andExpect(jsonPath("$.type").value("ONE_TIME"))
        .andExpect(jsonPath("$.placeName").value("강남역 11번 출구"))
        .andExpect(jsonPath("$.latitude").value(37.4979))
        .andExpect(jsonPath("$.longitude").value(127.0276))
        .andExpect(jsonPath("$.startDate").value("2026-12-04"))
        .andExpect(jsonPath("$.meetingTime").value("19:00:00"))
        .andExpect(jsonPath("$.graceMinutes").value(5))
        .andExpect(jsonPath("$.absentAfterMinutes").value(60))
        .andExpect(jsonPath("$.arrivalRadiusM").value(200))
        .andExpect(jsonPath("$.fineType").value("MONEY"))
        .andExpect(jsonPath("$.fineAmount").value(3000))
        .andExpect(jsonPath("$.penaltyText").value(nullValue()))
        .andExpect(jsonPath("$.myRole").value("OWNER"))
        .andExpect(jsonPath("$.createdAt").value(NOW.toString()));
  }

  @Test
  @DisplayName("멤버가 아니면 상세 조회가 403 NOT_GROUP_MEMBER로 거절된다")
  void get_nonMember_forbidden() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = getDetail(otherId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"))
        .andExpect(jsonPath("$.message").value("그룹 멤버만 할 수 있어요."));
  }

  @Test
  @DisplayName("없는 그룹을 조회하면 404 GROUP_NOT_FOUND로 거절된다")
  void get_missingGroup_notFound() throws Exception {
    // when
    ResultActions result = getDetail(ownerId, 999_999L);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  @Test
  @DisplayName("삭제된 그룹을 조회하면 404 GROUP_NOT_FOUND로 거절된다")
  void get_deletedGroup_notFound() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when
    ResultActions result = getDetail(ownerId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  // ---- 수정 ----

  @Test
  @DisplayName("방장이 이름을 30자로 바꾸면 DB의 이름이 바뀐다")
  void update_ownerWithMaxLengthName_renamesInDb() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    String name30 = "가".repeat(30);

    // when
    ResultActions result = rename(ownerId, groupId, name30);

    // then
    result.andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name30));
    assertThat(findGroup(groupId).getName()).isEqualTo(name30);
  }

  @Test
  @DisplayName("멤버가 아니면 이름 수정이 403 NOT_GROUP_MEMBER로 거절되고 이름이 그대로다")
  void update_nonMember_forbiddenAndUnchanged() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = rename(otherId, groupId, "토요일 모임");

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("일반 멤버가 이름을 바꾸면 403 OWNER_ONLY로 거절되고 이름이 그대로다")
  void update_plainMember_forbiddenWithOwnerOnlyAndUnchanged() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    addPlainMember(groupId, otherId);

    // when
    ResultActions result = rename(otherId, groupId, "토요일 모임");

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWNER_ONLY"))
        .andExpect(jsonPath("$.message").value("방장만 할 수 있어요."));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("일반 멤버도 그룹 상세는 볼 수 있고 myRole은 MEMBER다")
  void get_plainMember_returnsDetailAsMember() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    addPlainMember(groupId, otherId);

    // when
    ResultActions result = getDetail(otherId, groupId);

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(groupId))
        .andExpect(jsonPath("$.myRole").value("MEMBER"));
  }

  @Test
  @DisplayName("삭제된 그룹의 이름을 바꾸면 404 GROUP_NOT_FOUND로 거절되고 이름이 그대로다")
  void update_deletedGroup_notFoundAndUnchanged() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when
    ResultActions result = rename(ownerId, groupId, "토요일 모임");

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  @ParameterizedTest(name = "\"{0}\"")
  @MethodSource("invalidNames")
  @DisplayName("이름이 31자이거나 공백뿐이면 VALIDATION_FAILED로 거절되고 이름이 그대로다")
  void update_invalidName_rejectedAndUnchanged(String name) throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = rename(ownerId, groupId, name);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  static Stream<String> invalidNames() {
    return Stream.of("가".repeat(31), "   ");
  }

  // ---- 삭제 ----

  @Test
  @DisplayName("방장이 삭제하면 204를 주고, 행은 남은 채 서버 시각이 삭제 시각으로 기록된다")
  void delete_owner_softDeletesWithServerTime() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = deleteGroup(ownerId, groupId);

    // then
    result.andExpect(status().isNoContent());
    assertThat(findGroup(groupId).getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("이미 삭제된 그룹을 다시 삭제하면 404 GROUP_NOT_FOUND다")
  void delete_alreadyDeleted_notFound() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when
    ResultActions result = deleteGroup(ownerId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  @Test
  @DisplayName("멤버가 아니면 삭제가 403 NOT_GROUP_MEMBER로 거절되고 그룹이 삭제되지 않는다")
  void delete_nonMember_forbiddenAndNotDeleted() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result = deleteGroup(otherId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertThat(findGroup(groupId).getDeletedAt()).isNull();
  }

  @Test
  @DisplayName("일반 멤버가 삭제하면 403 OWNER_ONLY로 거절되고 그룹이 삭제되지 않는다")
  void delete_plainMember_forbiddenWithOwnerOnlyAndNotDeleted() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    addPlainMember(groupId, otherId);

    // when
    ResultActions result = deleteGroup(otherId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWNER_ONLY"))
        .andExpect(jsonPath("$.message").value("방장만 할 수 있어요."));
    assertThat(findGroup(groupId).getDeletedAt()).isNull();
    getDetail(ownerId, groupId).andExpect(status().isOk());
  }

  @Test
  @DisplayName("삭제된 그룹에 일반 멤버가 이름 수정을 요청하면 OWNER_ONLY가 아니라 404 GROUP_NOT_FOUND이고 이름이 그대로다")
  void update_plainMemberOnDeletedGroup_notFoundBeforeOwnerCheck() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    addPlainMember(groupId, otherId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when
    ResultActions result = rename(otherId, groupId, "토요일 모임");

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("삭제된 그룹에 일반 멤버가 삭제를 요청하면 OWNER_ONLY가 아니라 404 GROUP_NOT_FOUND이고 삭제 시각이 그대로다")
  void delete_plainMemberOnDeletedGroup_notFoundBeforeOwnerCheck() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    addPlainMember(groupId, otherId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when
    ResultActions result = deleteGroup(otherId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(findGroup(groupId).getDeletedAt()).isEqualTo(NOW);
  }

  // ---- 동시 수정 (낙관적 락) ----

  @Test
  @DisplayName("삭제 전에 읽은 그룹으로 이름을 바꿔 저장하면 낙관적 락으로 실패하고 삭제가 유지된다")
  void rename_withStaleGroupAfterDelete_failsAndKeepsDeletion() throws Exception {
    // given: 이름 수정 요청이 삭제 전에 그룹을 읽어 둔 상황
    Long groupId = createGroup(ownerId);
    MeetingGroup stale = findGroup(groupId);
    deleteGroup(ownerId, groupId).andExpect(status().isNoContent());

    // when & then: 읽어 둔 상태(deleted_at = null)로 저장하면 거절된다
    stale.rename("토요일 모임");
    assertThatThrownBy(() -> meetingGroupRepository.save(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    MeetingGroup current = findGroup(groupId);
    assertThat(current.getDeletedAt()).isEqualTo(NOW);
    assertThat(current.getName()).isEqualTo("금요일 모임");
  }
}
