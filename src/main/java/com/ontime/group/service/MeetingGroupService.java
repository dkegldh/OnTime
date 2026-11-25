package com.ontime.group.service;

import com.ontime.global.common.CursorResponse;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.dto.CreateMeetingGroupRequest;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.dto.MeetingGroupSummary;
import com.ontime.group.dto.UpdateMeetingGroupRequest;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.user.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingGroupService {

  private final MeetingGroupRepository meetingGroupRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final UserService userService;
  private final Clock clock;

  /** 일회성 약속 그룹을 만들고, 만든 사람을 방장으로 등록한다. */
  @Transactional
  public MeetingGroupResponse create(Long userId, CreateMeetingGroupRequest request) {
    MeetingGroup group =
        MeetingGroup.createOneTime(
            request.name(),
            request.toPlace(),
            request.startDate(),
            request.meetingTime(),
            request.toArrivalRule(),
            request.toFineRule(),
            Instant.now(clock));
    meetingGroupRepository.save(group);
    GroupMember owner =
        groupMemberRepository.save(GroupMember.owner(group, userService.getReference(userId)));
    return MeetingGroupResponse.of(owner);
  }

  public CursorResponse<MeetingGroupSummary> list(Long userId, Long cursor, int size) {
    List<MeetingGroupSummary> fetched =
        groupMemberRepository.findPageByUserId(userId, cursor, Limit.of(size + 1));
    return CursorResponse.of(fetched, size, MeetingGroupSummary::id);
  }

  public MeetingGroupResponse get(Long userId, Long groupId) {
    return MeetingGroupResponse.of(getMembership(userId, groupId));
  }

  @Transactional
  public MeetingGroupResponse update(Long userId, Long groupId, UpdateMeetingGroupRequest request) {
    GroupMember membership = getMembership(userId, groupId);
    membership.getGroup().rename(request.name());
    return MeetingGroupResponse.of(membership);
  }

  @Transactional
  public void delete(Long userId, Long groupId) {
    getMembership(userId, groupId).getGroup().delete(Instant.now(clock));
  }

  /**
   * 삭제되지 않은 그룹에서 사용자의 멤버 정보를 찾는다.
   *
   * <p>그룹이 없거나 삭제됐으면 {@code GROUP_NOT_FOUND}, 멤버가 아니면 {@code NOT_GROUP_MEMBER}.
   */
  private GroupMember getMembership(Long userId, Long groupId) {
    meetingGroupRepository
        .findByIdAndDeletedAtIsNull(groupId)
        .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
    return groupMemberRepository
        .findByGroupIdAndUserId(groupId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_GROUP_MEMBER));
  }
}
