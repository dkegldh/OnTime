package com.ontime.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.auth.LoginUserArgumentResolver;
import com.ontime.group.domain.GroupRole;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.support.MutableClock;
import com.ontime.support.MutableClockConfig;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 초대 코드, 참여, 나가기, 멤버 목록 API 통합 테스트. 실제 PostgreSQL, 시각은 옮길 수 있는 Clock 빈. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfig.class})
class GroupMemberControllerTest {

  private static final String BASE = "/api/v1/meeting-groups";
  private static final String USER_HEADER = LoginUserArgumentResolver.USER_ID_HEADER;

  // 서버 시각 2026-11-25 12:00 KST. 이때 발급한 코드는 2026-12-02T03:00:00Z 에 만료된다.
  private static final Instant NOW = MutableClockConfig.NOW;
  private static final Instant EXPIRES_AT = Instant.parse("2026-12-02T03:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MutableClock clock;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private MeetingRepository meetingRepository;
  @Autowired private AttendanceRepository attendanceRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private TransactionTemplate transactionTemplate;

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
            Map.entry("name", "금요일 모임"),
            Map.entry("placeName", "강남역"),
            Map.entry("latitude", 37.4979),
            Map.entry("longitude", 127.0276),
            Map.entry("startDate", "2026-12-31"),
            Map.entry("meetingTime", "19:00"),
            Map.entry("graceMinutes", 5),
            Map.entry("absentAfterMinutes", 60),
            Map.entry("arrivalRadiusM", 200),
            Map.entry("fineType", "MONEY"),
            Map.entry("fineAmount", 3000));
    String json =
        mockMvc
            .perform(
                post(BASE)
                    .header(USER_HEADER, userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private ResultActions issue(Long userId, Long groupId) throws Exception {
    return mockMvc.perform(
        post(BASE + "/" + groupId + "/invite-codes").header(USER_HEADER, userId.toString()));
  }

  private String issueCode(Long userId, Long groupId) throws Exception {
    String json =
        issue(userId, groupId)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("code").asString();
  }

  private ResultActions join(Long userId, String code) throws Exception {
    return mockMvc.perform(
        post(BASE + "/members")
            .header(USER_HEADER, userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("inviteCode", code))));
  }

  private ResultActions leave(Long userId, Long groupId) throws Exception {
    return mockMvc.perform(
        delete(BASE + "/" + groupId + "/members/me").header(USER_HEADER, userId.toString()));
  }

  private ResultActions listMembers(Long userId, Long groupId, String query) throws Exception {
    return mockMvc.perform(
        get(BASE + "/" + groupId + "/members" + query).header(USER_HEADER, userId.toString()));
  }

  private Long memberCount(Long groupId) {
    return transactionTemplate.execute(
        s ->
            groupMemberRepository.findAll().stream()
                .filter(m -> m.getGroup().getId().equals(groupId))
                .count());
  }

  private boolean isMember(Long userId, Long groupId) {
    return groupMemberRepository.findByGroupIdAndUserId(groupId, userId).isPresent();
  }

  // ---- 초대 코드 발급 ----

  @Test
  @DisplayName("방장이 초대 코드를 발급하면 201과 8자 코드, 7일 뒤 만료 시각을 준다")
  void issue_owner_returnsCodeExpiringInSevenDays() throws Exception {
    // when
    ResultActions result = issue(ownerId, groupId);

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(matchesRegex("[A-HJKMNP-Z2-9]{8}")))
        .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()));
    assertThat(inviteCodeRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("다시 발급하면 행은 하나로 유지되고 이전 코드로는 참여할 수 없다")
  void issue_again_invalidatesPreviousCode() throws Exception {
    // given
    String oldCode = issueCode(ownerId, groupId);

    // when
    String newCode = issueCode(ownerId, groupId);

    // then
    assertThat(newCode).isNotEqualTo(oldCode);
    assertThat(inviteCodeRepository.count()).isEqualTo(1);
    join(guestId, oldCode)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    join(guestId, newCode).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("멤버가 아니면 초대 코드 발급이 403 NOT_GROUP_MEMBER로 거절되고 코드가 생기지 않는다")
  void issue_nonMember_forbiddenAndNoCode() throws Exception {
    // when
    ResultActions result = issue(guestId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
    assertThat(inviteCodeRepository.count()).isZero();
  }

  @Test
  @DisplayName("삭제된 그룹의 초대 코드를 발급하면 404 GROUP_NOT_FOUND다")
  void issue_deletedGroup_notFound() throws Exception {
    // given
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = issue(ownerId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(inviteCodeRepository.count()).isZero();
  }

  @Test
  @DisplayName("일반 멤버가 초대 코드를 발급하면 403 OWNER_ONLY로 거절되고, 방장이 발급한 코드가 그대로 유효하다")
  void issue_plainMember_forbiddenAndOwnerCodeStillValid() throws Exception {
    // given: 손님이 방장 코드로 참여한 일반 멤버
    String ownerCode = issueCode(ownerId, groupId);
    join(guestId, ownerCode).andExpect(status().isCreated());
    Long thirdId = userRepository.save(User.create("셋째")).getId();

    // when
    ResultActions result = issue(guestId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWNER_ONLY"))
        .andExpect(jsonPath("$.message").value("방장만 할 수 있어요."));
    assertThat(inviteCodeRepository.findByGroupId(groupId))
        .get()
        .satisfies(
            c -> {
              assertThat(c.getCode()).isEqualTo(ownerCode);
              assertThat(c.getExpiresAt()).isEqualTo(EXPIRES_AT);
            });
    join(thirdId, ownerCode).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("삭제된 그룹에 일반 멤버가 초대 코드를 발급하면 OWNER_ONLY가 아니라 404 GROUP_NOT_FOUND이고 코드가 그대로다")
  void issue_plainMemberOnDeletedGroup_notFoundBeforeOwnerCheck() throws Exception {
    // given: 손님이 일반 멤버로 참여한 뒤 방장이 그룹을 삭제
    String ownerCode = issueCode(ownerId, groupId);
    join(guestId, ownerCode).andExpect(status().isCreated());
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = issue(guestId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(inviteCodeRepository.findByGroupId(groupId))
        .get()
        .satisfies(c -> assertThat(c.getCode()).isEqualTo(ownerCode));
  }

  // ---- 참여 ----

  @Test
  @DisplayName("초대 코드로 참여하면 201, Location, 그룹 상세(MEMBER)를 주고 MEMBER로 저장된다")
  void join_validCode_savesAsMember() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);

    // when
    ResultActions result = join(guestId, code);

    // then
    result
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", BASE + "/" + groupId))
        .andExpect(jsonPath("$.id").value(groupId))
        .andExpect(jsonPath("$.myRole").value("MEMBER"));
    GroupRole role =
        groupMemberRepository.findByGroupIdAndUserId(groupId, guestId).orElseThrow().getRole();
    assertThat(role).isEqualTo(GroupRole.MEMBER);
  }

  @Test
  @DisplayName("코드의 앞뒤 공백과 대소문자는 구분하지 않는다")
  void join_lowercaseCodeWithSpaces_joins() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);

    // when
    ResultActions result = join(guestId, "  " + code.toLowerCase() + " ");

    // then
    result.andExpect(status().isCreated());
    assertThat(isMember(guestId, groupId)).isTrue();
  }

  @Test
  @DisplayName("없는 코드로 참여하면 404 INVALID_INVITE_CODE이고 멤버가 늘지 않는다")
  void join_unknownCode_notFound() throws Exception {
    // given
    issueCode(ownerId, groupId);

    // when
    ResultActions result = join(guestId, "ZZZZ9999");

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    assertThat(memberCount(groupId)).isEqualTo(1);
  }

  @Test
  @DisplayName("삭제된 그룹의 코드로 참여하면 404 INVALID_INVITE_CODE이고 멤버가 되지 않는다")
  void join_deletedGroupCode_notFound() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = join(guestId, code);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVALID_INVITE_CODE"));
    assertThat(isMember(guestId, groupId)).isFalse();
  }

  @Test
  @DisplayName("만료 1초 전에는 참여할 수 있다")
  void join_oneSecondBeforeExpiry_joins() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);
    clock.setInstant(EXPIRES_AT.minusSeconds(1));

    // when & then
    join(guestId, code).andExpect(status().isCreated());
    assertThat(isMember(guestId, groupId)).isTrue();
  }

  @Test
  @DisplayName("만료 시각 정각에 참여하면 400 INVITE_CODE_EXPIRED이고 멤버가 되지 않는다")
  void join_atExpiry_rejectedAsExpired() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);
    clock.setInstant(EXPIRES_AT);

    // when
    ResultActions result = join(guestId, code);

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVITE_CODE_EXPIRED"))
        .andExpect(jsonPath("$.message").value("만료된 초대 코드예요. 새 코드를 받아 주세요."));
    assertThat(isMember(guestId, groupId)).isFalse();
  }

  @Test
  @DisplayName("이미 멤버면 409 ALREADY_MEMBER이고 멤버 수와 역할이 그대로다")
  void join_alreadyMember_conflictAndUnchanged() throws Exception {
    // given: 방장이 자기 그룹 코드로 참여 시도
    String code = issueCode(ownerId, groupId);

    // when
    ResultActions result = join(ownerId, code);

    // then
    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    assertThat(memberCount(groupId)).isEqualTo(1);
    assertThat(
            groupMemberRepository.findByGroupIdAndUserId(groupId, ownerId).orElseThrow().getRole())
        .isEqualTo(GroupRole.OWNER);
  }

  @Test
  @DisplayName("코드가 공백뿐이면 400 VALIDATION_FAILED다")
  void join_blankCode_rejected() throws Exception {
    // when
    ResultActions result = join(guestId, "   ");

    // then
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ---- 나가기 ----

  @Test
  @DisplayName("멤버가 나가면 204, 멤버 행이 지워지고 그룹 목록에서 빠지며 다시 참여할 수 있다")
  void leave_member_removesMembershipAndAllowsRejoin() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);
    join(guestId, code).andExpect(status().isCreated());

    // when
    ResultActions result = leave(guestId, groupId);

    // then
    result.andExpect(status().isNoContent());
    assertThat(isMember(guestId, groupId)).isFalse();
    mockMvc
        .perform(get(BASE).header(USER_HEADER, guestId.toString()))
        .andExpect(jsonPath("$.items", hasSize(0)));
    join(guestId, code).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("방장이 나가려 하면 409 OWNER_CANNOT_LEAVE이고 방장으로 남는다")
  void leave_owner_conflictAndStaysOwner() throws Exception {
    // when
    ResultActions result = leave(ownerId, groupId);

    // then
    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));
    assertThat(isMember(ownerId, groupId)).isTrue();
  }

  @Test
  @DisplayName("멤버가 아니면 나가기가 403 NOT_GROUP_MEMBER다")
  void leave_nonMember_forbidden() throws Exception {
    // when
    ResultActions result = leave(guestId, groupId);

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("삭제된 그룹에서 나가면 404 GROUP_NOT_FOUND이고 멤버 행은 그대로다")
  void leave_deletedGroup_notFoundAndUnchanged() throws Exception {
    // given
    String code = issueCode(ownerId, groupId);
    join(guestId, code).andExpect(status().isCreated());
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = leave(guestId, groupId);

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    assertThat(isMember(guestId, groupId)).isTrue();
  }

  // ---- 멤버 목록 ----

  @Test
  @DisplayName("멤버 목록은 참여 순으로 커서 페이지마다 나눠 주고, 닉네임과 역할, 참여 시각을 준다")
  void listMembers_paginatesInJoinOrder() throws Exception {
    // given: 방장(NOW) → 손님(NOW+1h) → 셋째(NOW+2h)
    String code = issueCode(ownerId, groupId);
    clock.setInstant(NOW.plusSeconds(3600));
    join(guestId, code).andExpect(status().isCreated());
    Long thirdId = userRepository.save(User.create("셋째")).getId();
    clock.setInstant(NOW.plusSeconds(7200));
    join(thirdId, code).andExpect(status().isCreated());

    // when: 첫 페이지
    ResultActions firstPage = listMembers(guestId, groupId, "?size=2");

    // then
    String json =
        firstPage
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[0].userId").value(ownerId))
            .andExpect(jsonPath("$.items[0].nickname").value("방장"))
            .andExpect(jsonPath("$.items[0].role").value("OWNER"))
            .andExpect(jsonPath("$.items[0].joinedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.items[1].userId").value(guestId))
            .andExpect(jsonPath("$.items[1].role").value("MEMBER"))
            .andExpect(jsonPath("$.items[1].joinedAt").value(NOW.plusSeconds(3600).toString()))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long cursor = objectMapper.readTree(json).get("nextCursor").asLong();
    long secondMemberId = objectMapper.readTree(json).get("items").get(1).get("memberId").asLong();
    assertThat(cursor).isEqualTo(secondMemberId);

    // when: 다음 페이지
    ResultActions secondPage = listMembers(guestId, groupId, "?size=2&cursor=" + cursor);

    // then
    secondPage
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].userId").value(thirdId))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  @DisplayName("멤버 목록에는 다른 그룹의 멤버가 섞이지 않는다")
  void listMembers_otherGroupHasMembers_returnsOnlyThisGroup() throws Exception {
    // given: 다른 그룹(방장: 남, 멤버: 손님)
    Long otherOwnerId = userRepository.save(User.create("남")).getId();
    Long otherGroupId = createGroup(otherOwnerId);
    join(guestId, issueCode(otherOwnerId, otherGroupId)).andExpect(status().isCreated());

    // when: 이 그룹(방장만 있음)의 멤버 목록
    ResultActions result = listMembers(ownerId, groupId, "");

    // then
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].userId").value(ownerId))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  @DisplayName("멤버 목록 size가 최소값 1이면 1명만 주고, 최대값 50이면 모두 준다")
  void listMembers_sizeAtBounds_accepted() throws Exception {
    // given: 방장 + 손님
    join(guestId, issueCode(ownerId, groupId)).andExpect(status().isCreated());

    // when & then
    listMembers(ownerId, groupId, "?size=1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.hasNext").value(true));
    listMembers(ownerId, groupId, "?size=50")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(2)))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  @DisplayName("멤버가 아니면 멤버 목록이 403 NOT_GROUP_MEMBER다")
  void listMembers_nonMember_forbidden() throws Exception {
    // when
    ResultActions result = listMembers(guestId, groupId, "");

    // then
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
  }

  @Test
  @DisplayName("삭제된 그룹의 멤버 목록은 404 GROUP_NOT_FOUND다")
  void listMembers_deletedGroup_notFound() throws Exception {
    // given
    mockMvc
        .perform(delete(BASE + "/" + groupId).header(USER_HEADER, ownerId.toString()))
        .andExpect(status().isNoContent());

    // when
    ResultActions result = listMembers(ownerId, groupId, "");

    // then
    result
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
  }

  @Test
  @DisplayName("멤버 목록 size가 0이거나 51이면 400 VALIDATION_FAILED다")
  void listMembers_sizeOutOfRange_rejected() throws Exception {
    // when & then
    listMembers(ownerId, groupId, "?size=0")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    listMembers(ownerId, groupId, "?size=51")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
