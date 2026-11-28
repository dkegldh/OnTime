package com.ontime.group.service;

import com.ontime.global.common.CursorResponse;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.dto.CreateMeetingGroupRequest;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.dto.MeetingGroupSummary;
import com.ontime.group.dto.UpdateMeetingGroupRequest;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.meeting.service.MeetingService;
import com.ontime.user.domain.User;
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
  private final GroupMembershipReader groupMembershipReader;
  private final UserService userService;
  private final MeetingService meetingService;
  private final Clock clock;

  /**
   * 그룹을 만들고, 만든 사람을 방장으로 등록한다. 같은 트랜잭션에서 회차와 방장의 참석 행도 만든다.
   *
   * <p>일회성 약속은 회차 1개, 정기 모임은 기간 안의 고른 요일마다 서버 시각 뒤에 열리는 회차 전부를 만든다.
   */
  @Transactional
  public MeetingGroupResponse create(Long userId, CreateMeetingGroupRequest request) {
    Instant now = Instant.now(clock);
    MeetingGroup group =
        switch (request.groupType()) {
          case ONE_TIME ->
              MeetingGroup.createOneTime(
                  request.name(),
                  request.toPlace(),
                  request.startDate(),
                  request.meetingTime(),
                  request.toArrivalRule(),
                  request.toFineRule(),
                  now);
          case RECURRING ->
              MeetingGroup.createRecurring(
                  request.name(),
                  request.toPlace(),
                  request.startDate(),
                  request.endDate(),
                  request.toWeekdays(),
                  request.meetingTime(),
                  request.toArrivalRule(),
                  request.toFineRule(),
                  now);
        };
    meetingGroupRepository.save(group);
    User user = userService.getReference(userId);
    GroupMember owner = groupMemberRepository.save(GroupMember.owner(group, user));
    switch (group.getType()) {
      case ONE_TIME -> meetingService.createOneTimeMeeting(group, user);
      case RECURRING -> meetingService.createRecurringMeetings(group, user, now);
    }
    return MeetingGroupResponse.of(owner);
  }

  public CursorResponse<MeetingGroupSummary> list(Long userId, Long cursor, int size) {
    List<MeetingGroupSummary> fetched =
        groupMemberRepository.findPageByUserId(userId, cursor, Limit.of(size + 1));
    return CursorResponse.of(fetched, size, MeetingGroupSummary::id);
  }

  public MeetingGroupResponse get(Long userId, Long groupId) {
    return MeetingGroupResponse.of(groupMembershipReader.getMembership(userId, groupId));
  }

  /** 그룹 이름을 바꾼다. 방장만 할 수 있다. */
  @Transactional
  public MeetingGroupResponse update(Long userId, Long groupId, UpdateMeetingGroupRequest request) {
    GroupMember membership = groupMembershipReader.getMembership(userId, groupId);
    membership.validateOwner();
    membership.getGroup().rename(request.name());
    return MeetingGroupResponse.of(membership);
  }

  /** 그룹을 soft delete 한다. 방장만 할 수 있다. */
  @Transactional
  public void delete(Long userId, Long groupId) {
    GroupMember membership = groupMembershipReader.getMembership(userId, groupId);
    membership.validateOwner();
    membership.getGroup().delete(Instant.now(clock));
  }
}
