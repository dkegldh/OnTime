package com.ontime.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

import com.ontime.global.common.CursorResponse;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.FineType;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.GroupRole;
import com.ontime.group.domain.GroupType;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.dto.CreateMeetingGroupRequest;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.dto.MeetingGroupSummary;
import com.ontime.group.dto.UpdateMeetingGroupRequest;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.user.domain.User;
import com.ontime.user.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class MeetingGroupServiceTest {

  // 서버 시각 2026-11-25 12:00 KST
  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  private static final Long USER_ID = 10L;
  private static final Long GROUP_ID = 1L;

  @Mock private MeetingGroupRepository meetingGroupRepository;
  @Mock private GroupMemberRepository groupMemberRepository;
  @Mock private UserService userService;

  private MeetingGroupService service;

  @BeforeEach
  void setUp() {
    service =
        new MeetingGroupService(
            meetingGroupRepository,
            groupMemberRepository,
            new GroupMembershipReader(meetingGroupRepository, groupMemberRepository),
            userService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static CreateMeetingGroupRequest request(LocalDate date, LocalTime time) {
    return new CreateMeetingGroupRequest(
        "금요일 모임", "강남역", 37.4979, 127.0276, date, time, 5, 60, 200, FineType.MONEY, 3000L,
        "무시되는 문구");
  }

  private static MeetingGroup group() {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        LocalDate.of(2026, 12, 4),
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

  private void givenPlainMember(MeetingGroup group) {
    given(meetingGroupRepository.findByIdAndDeletedAtIsNull(GROUP_ID))
        .willReturn(Optional.of(group));
    given(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .willReturn(Optional.of(GroupMember.member(group, User.create("멤버"))));
  }

  private void givenNonMember(MeetingGroup group) {
    given(meetingGroupRepository.findByIdAndDeletedAtIsNull(GROUP_ID))
        .willReturn(Optional.of(group));
    given(groupMemberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
        .willReturn(Optional.empty());
  }

  @Test
  @DisplayName("그룹을 만들면 요청자가 방장으로 등록되고, MONEY 규칙이면 벌칙 문구를 버린다")
  void create_validRequest_registersRequesterAsOwner() {
    // given
    User requester = User.create("방장");
    given(userService.getReference(USER_ID)).willReturn(requester);
    given(groupMemberRepository.save(any(GroupMember.class))).willAnswer(returnsFirstArg());

    // when
    MeetingGroupResponse response =
        service.create(USER_ID, request(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0)));

    // then
    ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
    verify(groupMemberRepository).save(captor.capture());
    GroupMember owner = captor.getValue();
    assertThat(owner.getUser()).isSameAs(requester);
    assertThat(owner.getRole()).isEqualTo(GroupRole.OWNER);
    assertThat(response.myRole()).isEqualTo(GroupRole.OWNER);
    assertThat(response.type()).isEqualTo(GroupType.ONE_TIME);
    assertThat(response.fineAmount()).isEqualTo(3000L);
    assertThat(response.penaltyText()).isNull();
  }

  @Test
  @DisplayName("약속 일시가 서버 시각과 같으면 MEETING_TIME_IN_PAST로 거절되고 아무것도 저장하지 않는다")
  void create_meetingAtNow_rejectedAndNothingSaved() {
    // given: NOW = 2026-11-25 12:00 KST
    CreateMeetingGroupRequest request = request(LocalDate.of(2026, 11, 25), LocalTime.of(12, 0));

    // when & then
    assertThatThrownBy(() -> service.create(USER_ID, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_TIME_IN_PAST);
    verify(meetingGroupRepository, never()).save(any());
    verify(groupMemberRepository, never()).save(any());
  }

  @Test
  @DisplayName("목록은 size + 1개를 조회해 다음 페이지가 있으면 마지막 항목 id를 커서로 준다")
  void list_moreThanSize_returnsNextCursorOfLastItem() {
    // given
    given(groupMemberRepository.findPageByUserId(USER_ID, null, Limit.of(3)))
        .willReturn(List.of(summary(30L), summary(20L), summary(10L)));

    // when
    CursorResponse<MeetingGroupSummary> response = service.list(USER_ID, null, 2);

    // then
    assertThat(response.items()).extracting(MeetingGroupSummary::id).containsExactly(30L, 20L);
    assertThat(response.hasNext()).isTrue();
    assertThat(response.nextCursor()).isEqualTo(20L);
  }

  @Test
  @DisplayName("목록 조회 결과가 size개 이하면 마지막 페이지다")
  void list_exactlySize_isLastPage() {
    // given
    given(groupMemberRepository.findPageByUserId(USER_ID, 50L, Limit.of(3)))
        .willReturn(List.of(summary(30L), summary(20L)));

    // when
    CursorResponse<MeetingGroupSummary> response = service.list(USER_ID, 50L, 2);

    // then
    assertThat(response.items()).extracting(MeetingGroupSummary::id).containsExactly(30L, 20L);
    assertThat(response.hasNext()).isFalse();
    assertThat(response.nextCursor()).isNull();
  }

  private static MeetingGroupSummary summary(Long id) {
    return new MeetingGroupSummary(
        id, "모임", GroupType.ONE_TIME, "강남역", LocalDate.of(2026, 12, 4), LocalTime.of(19, 0),
        GroupRole.OWNER);
  }

  @Test
  @DisplayName("멤버는 그룹 상세를 볼 수 있다")
  void get_member_returnsDetailWithRole() {
    // given
    givenMember(group());

    // when
    MeetingGroupResponse response = service.get(USER_ID, GROUP_ID);

    // then
    assertThat(response.name()).isEqualTo("금요일 모임");
    assertThat(response.myRole()).isEqualTo(GroupRole.OWNER);
  }

  @Test
  @DisplayName("없거나 삭제된 그룹을 조회하면 GROUP_NOT_FOUND로 거절된다")
  void get_missingGroup_rejectedWithGroupNotFound() {
    // given
    given(meetingGroupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> service.get(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.GROUP_NOT_FOUND);
  }

  @Test
  @DisplayName("멤버가 아니면 그룹 상세 조회가 NOT_GROUP_MEMBER로 거절된다")
  void get_nonMember_rejectedWithNotGroupMember() {
    // given
    givenNonMember(group());

    // when & then
    assertThatThrownBy(() -> service.get(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
  }

  @Test
  @DisplayName("방장이 이름을 바꾸면 그룹 이름이 바뀐다")
  void update_owner_renamesGroup() {
    // given
    MeetingGroup group = group();
    givenMember(group);

    // when
    MeetingGroupResponse response =
        service.update(USER_ID, GROUP_ID, new UpdateMeetingGroupRequest("토요일 모임"));

    // then
    assertThat(group.getName()).isEqualTo("토요일 모임");
    assertThat(response.name()).isEqualTo("토요일 모임");
  }

  @Test
  @DisplayName("멤버가 아니면 이름 수정이 NOT_GROUP_MEMBER로 거절되고 이름이 그대로다")
  void update_nonMember_rejectedAndNameUnchanged() {
    // given
    MeetingGroup group = group();
    givenNonMember(group);
    UpdateMeetingGroupRequest request = new UpdateMeetingGroupRequest("토요일 모임");

    // when & then
    assertThatThrownBy(() -> service.update(USER_ID, GROUP_ID, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    assertThat(group.getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("일반 멤버가 이름을 바꾸면 OWNER_ONLY로 거절되고 이름이 그대로다")
  void update_plainMember_rejectedWithOwnerOnlyAndNameUnchanged() {
    // given
    MeetingGroup group = group();
    givenPlainMember(group);
    UpdateMeetingGroupRequest request = new UpdateMeetingGroupRequest("토요일 모임");

    // when & then
    assertThatThrownBy(() -> service.update(USER_ID, GROUP_ID, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.OWNER_ONLY);
    assertThat(group.getName()).isEqualTo("금요일 모임");
  }

  @Test
  @DisplayName("방장이 삭제하면 서버 시각으로 삭제 시각이 기록된다")
  void delete_owner_recordsServerTimeAsDeletedAt() {
    // given
    MeetingGroup group = group();
    givenMember(group);

    // when
    service.delete(USER_ID, GROUP_ID);

    // then
    assertThat(group.getDeletedAt()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("멤버가 아니면 삭제가 NOT_GROUP_MEMBER로 거절되고 그룹이 삭제되지 않는다")
  void delete_nonMember_rejectedAndNotDeleted() {
    // given
    MeetingGroup group = group();
    givenNonMember(group);

    // when & then
    assertThatThrownBy(() -> service.delete(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    assertThat(group.getDeletedAt()).isNull();
  }

  @Test
  @DisplayName("일반 멤버가 삭제하면 OWNER_ONLY로 거절되고 그룹이 삭제되지 않는다")
  void delete_plainMember_rejectedWithOwnerOnlyAndNotDeleted() {
    // given
    MeetingGroup group = group();
    givenPlainMember(group);

    // when & then
    assertThatThrownBy(() -> service.delete(USER_ID, GROUP_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.OWNER_ONLY);
    assertThat(group.getDeletedAt()).isNull();
  }
}
