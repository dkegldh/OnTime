package com.ontime.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 그룹 CRUD API 통합 테스트. 실제 PostgreSQL에 저장하고, 서버 시각은 Clock mock으로 고정한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingGroupControllerTest {

  private static final String BASE = "/api/v1/meeting-groups";
  private static final String USER_HEADER = LoginUserArgumentResolver.USER_ID_HEADER;

  // 서버 시각 2026-11-25 12:00 KST
  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private Clock clock;

  private Long ownerId;
  private Long otherId;

  @BeforeEach
  void setUp() {
    given(clock.instant()).willReturn(NOW);
    ownerId = userRepository.save(User.create("방장")).getId();
    otherId = userRepository.save(User.create("남")).getId();
  }

  @AfterEach
  void tearDown() {
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  private static String body(
      String startDate, String meetingTime, int grace, int absentAfter, String fineJson) {
    return """
        {
          "name": "금요일 모임",
          "placeName": "강남역 11번 출구",
          "latitude": 37.4979,
          "longitude": 127.0276,
          "startDate": "%s",
          "meetingTime": "%s",
          "graceMinutes": %d,
          "absentAfterMinutes": %d,
          "arrivalRadiusM": 200,
          %s
        }
        """
        .formatted(startDate, meetingTime, grace, absentAfter, fineJson);
  }

  private static String validBody() {
    return body("2026-12-04", "19:00", 5, 60, "\"fineType\": \"MONEY\", \"fineAmount\": 3000");
  }

  private ResultActions create(Long userId, String body) throws Exception {
    return mockMvc.perform(
        post(BASE)
            .header(USER_HEADER, userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private Long createGroup(Long userId) throws Exception {
    String json = create(userId, validBody()).andReturn().getResponse().getContentAsString();
    JsonNode node = objectMapper.readTree(json);
    return node.get("id").asLong();
  }

  private ResultActions list(Long userId, String query) throws Exception {
    return mockMvc.perform(get(BASE + query).header(USER_HEADER, userId.toString()));
  }

  private MeetingGroup findGroup(Long groupId) {
    return meetingGroupRepository.findById(groupId).orElseThrow();
  }

  // ---- 생성 ----

  @Test
  @DisplayName("그룹을 만들면 201, Location, 상세를 돌려주고 요청자가 방장으로 저장된다")
  void create_validRequest_savesGroupAndOwner() throws Exception {
    // when
    ResultActions result = create(ownerId, validBody());

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("금요일 모임"))
        .andExpect(jsonPath("$.type").value("ONE_TIME"))
        .andExpect(jsonPath("$.startDate").value("2026-12-04"))
        .andExpect(jsonPath("$.meetingTime").value("19:00:00"))
        .andExpect(jsonPath("$.graceMinutes").value(5))
        .andExpect(jsonPath("$.absentAfterMinutes").value(60))
        .andExpect(jsonPath("$.arrivalRadiusM").value(200))
        .andExpect(jsonPath("$.fineType").value("MONEY"))
        .andExpect(jsonPath("$.fineAmount").value(3000))
        .andExpect(jsonPath("$.myRole").value("OWNER"));
    Long groupId =
        objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
            .get("id")
            .asLong();
    result.andExpect(header().string("Location", BASE + "/" + groupId));

    List<GroupMember> members = groupMemberRepository.findAll();
    assertThat(members).hasSize(1);
    Long memberUserId =
        transactionTemplate.execute(
            s -> groupMemberRepository.findAll().get(0).getUser().getId());
    assertThat(memberUserId).isEqualTo(ownerId);
    assertThat(members.get(0).getRole()).isEqualTo(GroupRole.OWNER);
  }

  @Test
  @DisplayName("벌칙(TEXT) 그룹은 금액을 보내도 0원으로 저장되고 벌칙 문구가 저장된다")
  void create_textFineWithAmount_savesZeroAmount() throws Exception {
    // given
    String body =
        body(
            "2026-12-04",
            "19:00",
            5,
            60,
            "\"fineType\": \"TEXT\", \"fineAmount\": 5000, \"penaltyText\": \"커피 사기\"");

    // when
    ResultActions result = create(ownerId, body);

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fineType").value("TEXT"))
        .andExpect(jsonPath("$.fineAmount").value(0))
        .andExpect(jsonPath("$.penaltyText").value("커피 사기"));
  }

  @Test
  @DisplayName("약속 일시가 서버 시각과 같으면 MEETING_TIME_IN_PAST로 거절되고 아무것도 저장되지 않는다")
  void create_meetingAtServerTime_rejectedAndNothingSaved() throws Exception {
    // given: 서버 시각 2026-11-25 12:00 KST
    String body = body("2026-11-25", "12:00", 5, 60, "\"fineType\": \"MONEY\", \"fineAmount\": 3000");

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
    String body = body("2026-11-25", "12:01", 5, 60, "\"fineType\": \"MONEY\", \"fineAmount\": 3000");

    // when & then
    create(ownerId, body).andExpect(status().isCreated());
    assertThat(meetingGroupRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("결석 판정 시간이 유예시간과 같으면 VALIDATION_FAILED로 거절되고 저장되지 않는다")
  void create_absentAfterEqualsGrace_rejected() throws Exception {
    // given
    String body = body("2026-12-04", "19:00", 10, 10, "\"fineType\": \"MONEY\", \"fineAmount\": 3000");

    // when & then
    create(ownerId, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(meetingGroupRepository.count()).isZero();
  }

  @Test
  @DisplayName("결석 판정 시간이 유예시간보다 1분 길면 만들어진다")
  void create_absentAfterOneMinuteLongerThanGrace_created() throws Exception {
    // given
    String body = body("2026-12-04", "19:00", 10, 11, "\"fineType\": \"MONEY\", \"fineAmount\": 3000");

    // when & then
    create(ownerId, body).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("MONEY인데 금액이 없으면 VALIDATION_FAILED로 거절되고 저장되지 않는다")
  void create_moneyWithoutAmount_rejected() throws Exception {
    // given
    String body = body("2026-12-04", "19:00", 5, 60, "\"fineType\": \"MONEY\"");

    // when & then
    create(ownerId, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(meetingGroupRepository.count()).isZero();
  }

  @Test
  @DisplayName("TEXT인데 벌칙 문구가 공백뿐이면 VALIDATION_FAILED로 거절되고 저장되지 않는다")
  void create_textWithBlankPenalty_rejected() throws Exception {
    // given
    String body =
        body("2026-12-04", "19:00", 5, 60, "\"fineType\": \"TEXT\", \"penaltyText\": \"  \"");

    // when & then
    create(ownerId, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(meetingGroupRepository.count()).isZero();
  }

  @Test
  @DisplayName("벌금 유형이 정의되지 않은 값이면 VALIDATION_FAILED로 거절된다")
  void create_unknownFineType_rejected() throws Exception {
    // given
    String body = body("2026-12-04", "19:00", 5, 60, "\"fineType\": \"COFFEE\"");

    // when & then
    create(ownerId, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(meetingGroupRepository.count()).isZero();
  }

  // ---- 목록 ----

  @Test
  @DisplayName("목록은 내가 속한, 삭제되지 않은 그룹만 id 내림차순으로 커서 페이지마다 나눠 준다")
  void list_paginatesOwnNonDeletedGroupsByIdDesc() throws Exception {
    // given: 내 그룹 3개 + 삭제한 내 그룹 1개 + 남의 그룹 1개
    Long first = createGroup(ownerId);
    Long second = createGroup(ownerId);
    Long deleted = createGroup(ownerId);
    mockMvc.perform(delete(BASE + "/" + deleted).header(USER_HEADER, ownerId.toString()));
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
  @DisplayName("size가 0이면 VALIDATION_FAILED로 거절된다")
  void list_sizeZero_rejected() throws Exception {
    list(ownerId, "?size=0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("size가 51이면 VALIDATION_FAILED로 거절된다")
  void list_sizeOverMax_rejected() throws Exception {
    list(ownerId, "?size=51")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("size가 최대값 50이면 조회된다")
  void list_sizeAtMax_ok() throws Exception {
    // given
    createGroup(ownerId);

    // when & then
    list(ownerId, "?size=50")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  // ---- 상세 ----

  @Test
  @DisplayName("멤버가 아니면 상세 조회가 403 NOT_GROUP_MEMBER로 거절된다")
  void get_nonMember_forbidden() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when & then
    mockMvc
        .perform(get(BASE + "/" + groupId).header(USER_HEADER, otherId.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("없는 그룹을 조회하면 404 GROUP_NOT_FOUND로 거절된다")
  void get_missingGroup_notFound() throws Exception {
    mockMvc
        .perform(get(BASE + "/999999").header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  // ---- 수정 ----

  @Test
  @DisplayName("멤버가 이름을 바꾸면 DB의 이름이 바뀐다")
  void update_member_renamesInDb() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result =
        mockMvc.perform(
            patch(BASE + "/" + groupId)
                .header(USER_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"토요일 모임\"}"));

    // then
    result.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("토요일 모임"));
    assertThat(findGroup(groupId).getName()).isEqualTo("토요일 모임");
  }

  @Test
  @DisplayName("멤버가 아니면 이름 수정이 403 NOT_GROUP_MEMBER로 거절되고 이름이 그대로다")
  void update_nonMember_forbiddenAndUnchanged() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result =
        mockMvc.perform(
            patch(BASE + "/" + groupId)
                .header(USER_HEADER, otherId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"토요일 모임\"}"));

    // then
    result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("이름이 31자면 VALIDATION_FAILED로 거절되고 이름이 그대로다")
  void update_nameOverMaxLength_rejectedAndUnchanged() throws Exception {
    // given
    Long groupId = createGroup(ownerId);
    String name31 = "가".repeat(31);

    // when
    ResultActions result =
        mockMvc.perform(
            patch(BASE + "/" + groupId)
                .header(USER_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name31 + "\"}"));

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(findGroup(groupId).getName()).isEqualTo("금요일 모임");
  }

  // ---- 삭제 ----

  @Test
  @DisplayName("삭제하면 204, 행은 남고 서버 시각이 삭제 시각으로 기록되며 이후 조회와 재삭제는 GROUP_NOT_FOUND다")
  void delete_member_softDeletesAndHidesGroup() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result =
        mockMvc.perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()));

    // then
    result.andExpect(status().isNoContent());
    assertThat(findGroup(groupId).getDeletedAt()).isEqualTo(NOW);
    mockMvc
        .perform(get(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  @Test
  @DisplayName("멤버가 아니면 삭제가 403 NOT_GROUP_MEMBER로 거절되고 그룹이 삭제되지 않는다")
  void delete_nonMember_forbiddenAndNotDeleted() throws Exception {
    // given
    Long groupId = createGroup(ownerId);

    // when
    ResultActions result =
        mockMvc.perform(delete(BASE + "/" + groupId).header(USER_HEADER, otherId.toString()));

    // then
    result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertThat(findGroup(groupId).getDeletedAt()).isNull();
  }
}
