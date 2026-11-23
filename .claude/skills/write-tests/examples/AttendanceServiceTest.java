package com.ontime.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.AttendanceFixture;
import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.repository.AttendanceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 예시: 서비스 단위 테스트 (Mockito).
 *
 * <p>Clock을 테스트마다 고정해서 "버튼 활성 시간(약속 3시간 전)" 경계를 검증한다. mock 호출 여부(verify)가 아니라 결과 상태와
 * ErrorCode를 본다.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

  // 약속 시각 2026-12-01 19:00 KST = 10:00 UTC → 버튼 활성 시각 07:00 UTC
  private static final Instant MEETING_AT = Instant.parse("2026-12-01T10:00:00Z");
  private static final Instant OPEN_AT = Instant.parse("2026-12-01T07:00:00Z");

  private static final Long MEETING_ID = 1L;
  private static final Long USER_ID = 10L;

  @Mock private AttendanceRepository attendanceRepository;

  private AttendanceService serviceAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new AttendanceService(attendanceRepository, clock);
  }

  @Test
  @DisplayName("약속 3시간 전 정각에 출발하면 MOVING이 된다")
  void depart_atOpenTime_becomesMoving() {
    // given
    Attendance attendance = AttendanceFixture.ready(MEETING_AT);
    given(attendanceRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID))
        .willReturn(Optional.of(attendance));

    // when
    serviceAt(OPEN_AT).depart(MEETING_ID, USER_ID);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(attendance.getDepartedAt()).isEqualTo(OPEN_AT);
  }

  @Test
  @DisplayName("약속 3시간 전보다 1초 이르게 출발하면 MEETING_NOT_OPEN으로 거절되고 상태는 그대로다")
  void depart_oneSecondBeforeOpen_rejectedAndStatusUnchanged() {
    // given
    Attendance attendance = AttendanceFixture.ready(MEETING_AT);
    given(attendanceRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID))
        .willReturn(Optional.of(attendance));

    // when & then
    assertThatThrownBy(() -> serviceAt(OPEN_AT.minusSeconds(1)).depart(MEETING_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_OPEN);
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.READY);
    assertThat(attendance.getDepartedAt()).isNull();
  }

  @Test
  @DisplayName("회차 멤버가 아니면 ATTENDANCE_NOT_FOUND로 거절된다")
  void depart_notMember_rejectedAsNotFound() {
    // given
    given(attendanceRepository.findByMeetingIdAndUserId(MEETING_ID, USER_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> serviceAt(OPEN_AT).depart(MEETING_ID, USER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ATTENDANCE_NOT_FOUND);
  }
}
