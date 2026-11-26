package com.ontime.meeting.service;

import com.ontime.global.common.CursorResponse;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.service.GroupMembershipReader;
import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.domain.MeetingStatus;
import com.ontime.meeting.dto.AttendanceResponse;
import com.ontime.meeting.dto.MeetingDetailResponse;
import com.ontime.meeting.dto.MeetingSummary;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차와 참석.
 *
 * <p>회차/참석 행 생성과 정리는 group 서비스(그룹 생성, 참여, 나가기)가 자기 트랜잭션 안에서 호출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingService {

  private final MeetingRepository meetingRepository;
  private final AttendanceRepository attendanceRepository;
  private final GroupMembershipReader groupMembershipReader;
  private final Clock clock;

  /** 일회성 그룹의 회차를 만들고 방장을 READY로 등록한다. */
  @Transactional
  public void createOneTimeMeeting(MeetingGroup group, User owner) {
    Meeting meeting = meetingRepository.save(Meeting.oneTimeOf(group));
    attendanceRepository.save(Attendance.ready(meeting, owner));
  }

  /** 새로 참여한 멤버를 그룹의 아직 시작하지 않은 회차에 READY로 등록한다. */
  @Transactional
  public void addToUpcomingMeetings(MeetingGroup group, User user, Instant now) {
    List<Attendance> attendances =
        meetingRepository.findUpcoming(group.getId(), MeetingStatus.SCHEDULED, now).stream()
            .map(meeting -> Attendance.ready(meeting, user))
            .toList();
    attendanceRepository.saveAll(attendances);
  }

  /**
   * 그룹을 나간 멤버의, 아직 시작하지 않은 회차의 참석 행을 지운다.
   *
   * <p>남겨 두면 나간 멤버가 상세에 READY로 보이고, 이후 지각 스케줄러가 벌금을 매긴다. 이미 시작한 회차의 기록은 남긴다.
   */
  @Transactional
  public void removeFromUpcomingMeetings(Long groupId, Long userId, Instant now) {
    attendanceRepository.deleteUpcoming(groupId, userId, MeetingStatus.SCHEDULED, now);
  }

  /** 그룹의 회차 목록. 그룹이 없거나 삭제됐으면 {@code GROUP_NOT_FOUND}, 멤버가 아니면 {@code NOT_GROUP_MEMBER}. */
  public CursorResponse<MeetingSummary> list(Long userId, Long groupId, Long cursor, int size) {
    groupMembershipReader.getMembership(userId, groupId);
    List<MeetingSummary> fetched =
        meetingRepository.findPageByGroupId(groupId, userId, cursor, Limit.of(size + 1));
    return CursorResponse.of(fetched, size, MeetingSummary::id);
  }

  /**
   * 회차 상세와 멤버별 상태.
   *
   * <p>없는 회차와 삭제된 그룹의 회차는 {@code MEETING_NOT_FOUND}, 그룹 멤버가 아니면 {@code NOT_GROUP_MEMBER}.
   */
  public MeetingDetailResponse get(Long userId, Long meetingId) {
    Meeting meeting = getMeetingForMember(userId, meetingId);
    List<AttendanceResponse> attendances =
        attendanceRepository.findResponsesByMeetingId(meetingId);
    return MeetingDetailResponse.of(meeting, userId, attendances);
  }

  /**
   * 출발: 요청자의 참석 상태를 {@code READY → MOVING} 으로 바꾸고 서버 시각을 출발 시각으로 기록한다.
   *
   * <p>회차/멤버 검사는 {@link #get} 과 같다. 이 회차에 참석 행이 없거나(시작 뒤 참여) 전이 규칙에 맞지 않으면 {@code
   * INVALID_STATE}, 약속 3시간 전보다 이르면 {@code MEETING_NOT_OPEN}. 같은 행을 동시에 바꾸면 {@code @Version} 충돌로
   * 나중 커밋이 실패한다.
   */
  @Transactional
  public AttendanceResponse depart(Long userId, Long meetingId) {
    getMeetingForMember(userId, meetingId);
    Attendance attendance =
        attendanceRepository
            .findWithUserByMeetingIdAndUserId(meetingId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE));
    attendance.depart(Instant.now(clock));
    return AttendanceResponse.from(attendance);
  }

  /** 없는 회차와 삭제된 그룹의 회차는 {@code MEETING_NOT_FOUND}, 그룹 멤버가 아니면 {@code NOT_GROUP_MEMBER}. */
  private Meeting getMeetingForMember(Long userId, Long meetingId) {
    Meeting meeting =
        meetingRepository
            .findWithGroupById(meetingId)
            .filter(m -> m.getGroup().getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_FOUND));
    groupMembershipReader.getMembership(userId, meeting.getGroup().getId());
    return meeting;
  }
}
