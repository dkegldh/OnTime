package com.ontime.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.service.GroupMembershipReader;
import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.dto.AttendanceResponse;
import com.ontime.meeting.dto.MeetingDetailResponse;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.user.domain.User;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  private static final Long USER_ID = 10L;
  private static final Long OTHER_ID = 20L;
  private static final Long MEETING_ID = 100L;
  // 회차 id 와 다른 값이어야 멤버 확인에 회차 id 를 넘기는 실수를 잡는다.
  private static final Long GROUP_ID = 1L;

  // 서비스가 출발 시각으로 쓰는 서버 시각. 약속(2026-12-04T10:00:00Z) 30분 전이라 버튼이 열려 있다.
  private static final Instant DEPART_NOW = Instant.parse("2026-12-04T09:30:00Z");

  @Mock private MeetingRepository meetingRepository;
  @Mock private AttendanceRepository attendanceRepository;
  @Mock private GroupMembershipReader groupMembershipReader;

  private MeetingService service;

  @BeforeEach
  void setUp() {
    service = new MeetingService(
            meetingRepository,
            attendanceRepository,
            groupMembershipReader,
            Clock.fixed(DEPART_NOW, ZoneOffset.UTC));
  }

  private static MeetingGroup group() {
    MeetingGroup group =
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", 37.4979, 127.0276),
            LocalDate.of(2026, 12, 4),
            LocalTime.of(19, 0),
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            NOW);
    ReflectionTestUtils.setField(group, "id", GROUP_ID);
    return group;
  }

  private static AttendanceResponse attendance(Long userId, AttendanceStatus status) {
    return new AttendanceResponse(userId, "닉네임" + userId, null, status, null, null);
  }

  private void givenMeeting(Meeting meeting) {
    given(meetingRepository.findWithGroupById(MEETING_ID)).willReturn(Optional.of(meeting));
  }

  @Test
  @DisplayName("없는 회차를 조회하면 MEETING_NOT_FOUND로 거절된다")
  void get_missingMeeting_rejectedWithMeetingNotFound() {
    // given
    given(meetingRepository.findWithGroupById(MEETING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> service.get(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
  }

  @Test
  @DisplayName("삭제된 그룹의 회차를 조회하면 멤버 확인 전에 MEETING_NOT_FOUND로 거절된다")
  void get_deletedGroup_rejectedWithMeetingNotFoundBeforeMembershipCheck() {
    // given
    MeetingGroup group = group();
    Meeting meeting = Meeting.oneTimeOf(group);
    group.delete(NOW);
    givenMeeting(meeting);

    // when & then
    assertThatThrownBy(() -> service.get(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    verify(groupMembershipReader, never()).getMembership(any(), any());
    verify(attendanceRepository, never()).findResponsesByMeetingId(any());
  }

  @Test
  @DisplayName("그룹 멤버가 아니면 NOT_GROUP_MEMBER로 거절되고 참석 목록을 읽지 않는다")
  void get_nonMember_rejectedWithNotGroupMember() {
    // given
    givenMeeting(Meeting.oneTimeOf(group()));
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willThrow(new BusinessException(ErrorCode.NOT_GROUP_MEMBER));

    // when & then
    assertThatThrownBy(() -> service.get(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    verify(attendanceRepository, never()).findResponsesByMeetingId(any());
  }

  @Test
  @DisplayName("상세의 내 상태는 참석 목록에서 요청자의 상태다")
  void get_requesterInAttendances_myStatusIsRequesterStatus() {
    // given
    MeetingGroup group = group();
    givenMeeting(Meeting.oneTimeOf(group));
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willReturn(GroupMember.member(group, User.create("멤버")));
    given(attendanceRepository.findResponsesByMeetingId(MEETING_ID))
        .willReturn(
            List.of(
                attendance(OTHER_ID, AttendanceStatus.READY),
                attendance(USER_ID, AttendanceStatus.MOVING)));

    // when
    MeetingDetailResponse response = service.get(USER_ID, MEETING_ID);

    // then
    assertThat(response.myStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(response.attendances())
        .extracting(AttendanceResponse::userId)
        .containsExactly(OTHER_ID, USER_ID);
  }

  @Test
  @DisplayName("요청자의 참석 행이 없으면(회차 시작 뒤 참여) 내 상태는 null이다")
  void get_requesterNotInAttendances_myStatusNull() {
    // given
    MeetingGroup group = group();
    givenMeeting(Meeting.oneTimeOf(group));
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willReturn(GroupMember.member(group, User.create("멤버")));
    given(attendanceRepository.findResponsesByMeetingId(MEETING_ID))
        .willReturn(List.of(attendance(OTHER_ID, AttendanceStatus.READY)));

    // when
    MeetingDetailResponse response = service.get(USER_ID, MEETING_ID);

    // then
    assertThat(response.myStatus()).isNull();
    assertThat(response.attendances()).hasSize(1);
  }

  // ---- 출발 ----

  @Test
  @DisplayName("출발하면 내 참석 행이 MOVING이 되고 출발 시각은 주입받은 Clock의 시각이다")
  void depart_member_movesWithClockTime() {
    // given
    MeetingGroup group = group();
    Meeting meeting = Meeting.oneTimeOf(group);
    givenMeeting(meeting);
    User user = User.create("멤버");
    ReflectionTestUtils.setField(user, "id", USER_ID);
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willReturn(GroupMember.member(group, user));
    Attendance attendance = Attendance.ready(meeting, user);
    given(attendanceRepository.findWithUserByMeetingIdAndUserId(MEETING_ID, USER_ID))
        .willReturn(Optional.of(attendance));

    // when
    AttendanceResponse response = service.depart(USER_ID, MEETING_ID);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(attendance.getDepartedAt()).isEqualTo(DEPART_NOW);
    assertThat(response.userId()).isEqualTo(USER_ID);
    assertThat(response.status()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(response.departedAt()).isEqualTo(DEPART_NOW);
  }

  @Test
  @DisplayName("없는 회차에 출발하면 MEETING_NOT_FOUND로 거절된다")
  void depart_missingMeeting_rejectedWithMeetingNotFound() {
    // given
    given(meetingRepository.findWithGroupById(MEETING_ID)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> service.depart(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    verify(attendanceRepository, never()).findWithUserByMeetingIdAndUserId(any(), any());
  }

  @Test
  @DisplayName("삭제된 그룹의 회차에 출발하면 멤버 확인 전에 MEETING_NOT_FOUND로 거절된다")
  void depart_deletedGroup_rejectedWithMeetingNotFound() {
    // given
    MeetingGroup group = group();
    Meeting meeting = Meeting.oneTimeOf(group);
    group.delete(NOW);
    givenMeeting(meeting);

    // when & then
    assertThatThrownBy(() -> service.depart(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_FOUND);
    verify(groupMembershipReader, never()).getMembership(any(), any());
    verify(attendanceRepository, never()).findWithUserByMeetingIdAndUserId(any(), any());
  }

  @Test
  @DisplayName("그룹 멤버가 아니면 출발이 NOT_GROUP_MEMBER로 거절되고 참석 행을 읽지 않는다")
  void depart_nonMember_rejectedWithNotGroupMember() {
    // given
    givenMeeting(Meeting.oneTimeOf(group()));
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willThrow(new BusinessException(ErrorCode.NOT_GROUP_MEMBER));

    // when & then
    assertThatThrownBy(() -> service.depart(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    verify(attendanceRepository, never()).findWithUserByMeetingIdAndUserId(any(), any());
  }

  @Test
  @DisplayName("멤버지만 이 회차에 참석 행이 없으면(시작 뒤 참여) 출발이 INVALID_STATE로 거절된다")
  void depart_memberWithoutAttendance_rejectedWithInvalidState() {
    // given
    MeetingGroup group = group();
    givenMeeting(Meeting.oneTimeOf(group));
    given(groupMembershipReader.getMembership(USER_ID, GROUP_ID))
        .willReturn(GroupMember.member(group, User.create("멤버")));
    given(attendanceRepository.findWithUserByMeetingIdAndUserId(MEETING_ID, USER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> service.depart(USER_ID, MEETING_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE);
  }
}
