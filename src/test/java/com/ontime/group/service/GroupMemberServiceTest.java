package com.ontime.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.InviteCode;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.dto.InviteCodeResponse;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.user.domain.User;
import com.ontime.user.service.UserService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** 통합 테스트로 재현하기 어려운 분기(코드 충돌 재시도, 유니크 제약 위반 해석)를 본다. */
@ExtendWith(MockitoExtension.class)
class GroupMemberServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  private static final Long USER_ID = 10L;
  private static final Long GROUP_ID = 1L;

  @Mock private InviteCodeRepository inviteCodeRepository;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private MeetingGroupRepository meetingGroupRepository;
  @Mock private InviteCodeGenerator inviteCodeGenerator;
  @Mock private UserService userService;

  private GroupMemberService service;

  @BeforeEach
  void setUp() {
    service =
        new GroupMemberService(
            inviteCodeRepository,
            groupMemberRepository,
            new GroupMembershipReader(meetingGroupRepository, groupMemberRepository),
            inviteCodeGenerator,
            userService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static MeetingGroup group() {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        LocalDate.of(2026, 12, 31),
        LocalTime.of(19, 0),
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        NOW);
  }

  private void givenMember(MeetingGroup group) {
    given(meetingGroupRepository.findByIdAndDeletedAtIsNull(GROUP_ID))
        .willReturn(Optional.of(group));
    given(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .willReturn(Optional.of(GroupMember.owner(group, User.create("방장"))));
  }

  private static DataIntegrityViolationException violationOf(String constraintName) {
    return new DataIntegrityViolationException(
        "중복",
        new ConstraintViolationException("중복", new SQLException("dup"), constraintName));
  }

  /** 참여 직전까지(코드 유효, 아직 멤버 아님)를 준비한다. join 은 group.getId() 를 쓰므로 null id 로 조회된다. */
  private void givenJoinableCode() {
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", NOW);
    given(inviteCodeRepository.findWithGroupByCode("ABCD2345"))
        .willReturn(Optional.of(inviteCode));
    given(groupMemberRepository.findByGroupIdAndUserId(null, USER_ID)).willReturn(Optional.empty());
    given(userService.getReference(USER_ID)).willReturn(User.create("참여자"));
  }

  @Test
  @DisplayName("동시 참여로 (group_id, user_id) 유니크 제약을 어기면 ALREADY_MEMBER로 응답한다")
  void join_duplicateMembershipConstraint_rejectedAsAlreadyMember() {
    // given
    givenJoinableCode();
    given(groupMemberRepository.saveAndFlush(any(GroupMember.class)))
        .willThrow(violationOf(GroupMember.UNIQUE_GROUP_USER));

    // when & then
    assertThatThrownBy(() -> service.join(USER_ID, "ABCD2345"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ALREADY_MEMBER);
  }

  @Test
  @DisplayName("다른 제약 위반은 ALREADY_MEMBER로 바꾸지 않고 그대로 던진다")
  void join_otherConstraintViolation_rethrown() {
    // given
    givenJoinableCode();
    DataIntegrityViolationException other = violationOf("fk_group_members_user");
    given(groupMemberRepository.saveAndFlush(any(GroupMember.class))).willThrow(other);

    // when & then
    assertThatThrownBy(() -> service.join(USER_ID, "ABCD2345")).isSameAs(other);
  }

  @Test
  @DisplayName("만든 코드가 이미 쓰이고 있으면 다시 만들어 겹치지 않는 코드를 발급한다")
  void issueInviteCode_codeCollision_retriesWithNewCode() {
    // given
    givenMember(group());
    given(inviteCodeGenerator.generate()).willReturn("AAAA2222", "BBBB3333");
    given(inviteCodeRepository.existsByCode("AAAA2222")).willReturn(true);
    given(inviteCodeRepository.existsByCode("BBBB3333")).willReturn(false);
    given(inviteCodeRepository.findByGroupId(GROUP_ID)).willReturn(Optional.empty());
    given(inviteCodeRepository.save(any(InviteCode.class))).willAnswer(i -> i.getArgument(0));

    // when
    InviteCodeResponse response = service.issueInviteCode(USER_ID, GROUP_ID);

    // then
    assertThat(response.code()).isEqualTo("BBBB3333");
    assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-12-02T03:00:00Z"));
  }

  @Test
  @DisplayName("5번 모두 겹치면 발급을 포기하고 아무것도 저장하지 않는다")
  void issueInviteCode_allAttemptsCollide_failsWithoutSaving() {
    // given
    givenMember(group());
    given(inviteCodeGenerator.generate()).willReturn("AAAA2222");
    given(inviteCodeRepository.existsByCode("AAAA2222")).willReturn(true);

    // when & then
    assertThatThrownBy(() -> service.issueInviteCode(USER_ID, GROUP_ID))
        .isInstanceOf(IllegalStateException.class);
    verify(inviteCodeGenerator, times(5)).generate();
    verify(inviteCodeRepository, never()).save(any());
  }

  @Test
  @DisplayName("이미 코드가 있으면 새 행을 만들지 않고 같은 행의 코드와 만료 시각을 바꾼다")
  void issueInviteCode_existingCode_reissuesSameRow() {
    // given
    MeetingGroup group = group();
    givenMember(group);
    InviteCode existing = InviteCode.issue(group, "OLDC2345", Instant.parse("2026-11-20T00:00:00Z"));
    given(inviteCodeGenerator.generate()).willReturn("NEWC6789");
    given(inviteCodeRepository.existsByCode("NEWC6789")).willReturn(false);
    given(inviteCodeRepository.findByGroupId(GROUP_ID)).willReturn(Optional.of(existing));

    // when
    InviteCodeResponse response = service.issueInviteCode(USER_ID, GROUP_ID);

    // then
    assertThat(existing.getCode()).isEqualTo("NEWC6789");
    assertThat(existing.getExpiresAt()).isEqualTo(Instant.parse("2026-12-02T03:00:00Z"));
    assertThat(response.code()).isEqualTo("NEWC6789");
    verify(inviteCodeRepository, never()).save(any());
  }

  @Test
  @DisplayName("4번 겹치고 5번째에 겹치지 않으면 5번째 코드로 발급한다")
  void issueInviteCode_fifthAttemptUnique_issues() {
    // given
    givenMember(group());
    given(inviteCodeGenerator.generate())
        .willReturn("AAAA2222", "AAAA2223", "AAAA2224", "AAAA2225", "BBBB3333");
    given(inviteCodeRepository.existsByCode(startsWith("AAAA")))
        .willReturn(true);
    given(inviteCodeRepository.existsByCode("BBBB3333")).willReturn(false);
    given(inviteCodeRepository.findByGroupId(GROUP_ID)).willReturn(Optional.empty());
    given(inviteCodeRepository.save(any(InviteCode.class))).willAnswer(i -> i.getArgument(0));

    // when
    InviteCodeResponse response = service.issueInviteCode(USER_ID, GROUP_ID);

    // then
    assertThat(response.code()).isEqualTo("BBBB3333");
  }

  private void givenFirstIssue() {
    givenMember(group());
    given(inviteCodeGenerator.generate()).willReturn("CCCC4444");
    given(inviteCodeRepository.existsByCode("CCCC4444")).willReturn(false);
    given(inviteCodeRepository.findByGroupId(GROUP_ID)).willReturn(Optional.empty());
    given(inviteCodeRepository.save(any(InviteCode.class))).willAnswer(i -> i.getArgument(0));
  }

  @Test
  @DisplayName("같은 그룹 첫 발급이 동시에 와서 그룹당 1개 제약을 어기면 CONCURRENT_UPDATE로 응답한다")
  void issueInviteCode_groupUniqueViolation_rejectedAsConcurrentUpdate() {
    // given
    givenFirstIssue();
    willThrow(violationOf(InviteCode.UNIQUE_GROUP)).given(inviteCodeRepository).flush();

    // when & then
    assertThatThrownBy(() -> service.issueInviteCode(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONCURRENT_UPDATE);
  }

  @Test
  @DisplayName("다른 그룹과 동시에 같은 코드를 만들어 코드 유니크 제약을 어기면 CONCURRENT_UPDATE로 응답한다")
  void issueInviteCode_codeUniqueViolation_rejectedAsConcurrentUpdate() {
    // given
    givenFirstIssue();
    willThrow(violationOf(InviteCode.UNIQUE_CODE)).given(inviteCodeRepository).flush();

    // when & then
    assertThatThrownBy(() -> service.issueInviteCode(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONCURRENT_UPDATE);
  }

  @Test
  @DisplayName("발급 중 다른 제약 위반은 CONCURRENT_UPDATE로 바꾸지 않고 그대로 던진다")
  void issueInviteCode_otherConstraintViolation_rethrown() {
    // given
    givenFirstIssue();
    DataIntegrityViolationException other = violationOf("fk_invite_codes_group");
    willThrow(other).given(inviteCodeRepository).flush();

    // when & then
    assertThatThrownBy(() -> service.issueInviteCode(USER_ID, GROUP_ID)).isSameAs(other);
  }

  @Test
  @DisplayName("재발급 중 다른 그룹과 같은 코드가 되어 코드 유니크 제약을 어기면 CONCURRENT_UPDATE로 응답한다")
  void issueInviteCode_reissueCodeUniqueViolation_rejectedAsConcurrentUpdate() {
    // given: 이미 코드가 있는 그룹
    MeetingGroup group = group();
    givenMember(group);
    InviteCode existing = InviteCode.issue(group, "OLDC2345", NOW);
    given(inviteCodeGenerator.generate()).willReturn("DDDD5555");
    given(inviteCodeRepository.existsByCode("DDDD5555")).willReturn(false);
    given(inviteCodeRepository.findByGroupId(GROUP_ID)).willReturn(Optional.of(existing));
    willThrow(violationOf(InviteCode.UNIQUE_CODE)).given(inviteCodeRepository).flush();

    // when & then
    assertThatThrownBy(() -> service.issueInviteCode(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONCURRENT_UPDATE);
  }
}
