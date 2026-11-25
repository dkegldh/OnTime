package com.ontime.group.service;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 그룹 서비스들이 같이 쓰는 "삭제되지 않은 그룹의 멤버인가" 검사. 호출하는 서비스의 트랜잭션 안에서 쓴다. */
@Component
@RequiredArgsConstructor
public class GroupMembershipReader {

  private final MeetingGroupRepository meetingGroupRepository;
  private final GroupMemberRepository groupMemberRepository;

  /**
   * 삭제되지 않은 그룹에서 사용자의 멤버 정보를 찾는다.
   *
   * <p>그룹이 없거나 삭제됐으면 {@code GROUP_NOT_FOUND}, 멤버가 아니면 {@code NOT_GROUP_MEMBER}.
   */
  public GroupMember getMembership(Long userId, Long groupId) {
    meetingGroupRepository
        .findByIdAndDeletedAtIsNull(groupId)
        .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
    return groupMemberRepository
        .findByGroupIdAndUserId(groupId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_GROUP_MEMBER));
  }
}
