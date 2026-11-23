package com.ontime.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 예시: 엔티티 단위 테스트. Spring 없이 엔티티의 상태 전이와 경계값만 검증한다.
 *
 * <p>약속 19:00 KST (= 10:00 UTC), 유예 5분 → 유예 마감 10:05:00Z.
 * 기대값은 모두 손으로 계산한 상수다.
 */
class AttendanceTest {

  private static final Instant GRACE_DEADLINE = Instant.parse("2026-12-01T10:05:00Z");

  // 약속 장소 근처 좌표 (반경 200m 기준으로 안/밖이 되도록 미리 계산한 값)
  private static final double INSIDE_LAT = 37.49800;
  private static final double INSIDE_LNG = 127.02760;
  private static final double OUTSIDE_LAT = 37.50200; // 약 450m 떨어짐
  private static final double OUTSIDE_LNG = 127.02760;

  @Nested
  @DisplayName("도착")
  class Arrive {

    @Test
    @DisplayName("유예시간 정각에 도착하면 ARRIVED가 된다")
    void arrive_atGraceDeadline_becomesArrived() {
      // given
      Attendance attendance = AttendanceFixture.moving(GRACE_DEADLINE);

      // when
      attendance.arrive(GRACE_DEADLINE, INSIDE_LAT, INSIDE_LNG);

      // then
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
      assertThat(attendance.getArrivedAt()).isEqualTo(GRACE_DEADLINE);
    }

    @Test
    @DisplayName("유예시간 1초 전에 도착하면 ARRIVED가 된다")
    void arrive_oneSecondBeforeGraceDeadline_becomesArrived() {
      // given
      Attendance attendance = AttendanceFixture.moving(GRACE_DEADLINE);

      // when
      attendance.arrive(GRACE_DEADLINE.minusSeconds(1), INSIDE_LAT, INSIDE_LNG);

      // then
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    }

    @Test
    @DisplayName("LATE 상태에서 도착하면 LATE_ARRIVED가 된다")
    void arrive_whenLate_becomesLateArrived() {
      // given
      Attendance attendance = AttendanceFixture.late(GRACE_DEADLINE);

      // when
      attendance.arrive(GRACE_DEADLINE.plusSeconds(60), INSIDE_LAT, INSIDE_LNG);

      // then
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
    }

    @Test
    @DisplayName("반경 밖에서 도착하면 ARRIVE_TOO_FAR로 거절되고 상태는 그대로다")
    void arrive_outsideRadius_rejectedAndStatusUnchanged() {
      // given
      Attendance attendance = AttendanceFixture.moving(GRACE_DEADLINE);

      // when & then
      assertThatThrownBy(() -> attendance.arrive(GRACE_DEADLINE, OUTSIDE_LAT, OUTSIDE_LNG))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.ARRIVE_TOO_FAR);
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
      assertThat(attendance.getArrivedAt()).isNull();
    }

    @Test
    @DisplayName("ARRIVED 상태에서 다시 도착하면 INVALID_STATE로 거절된다")
    void arrive_whenAlreadyArrived_rejectedAsInvalidState() {
      // given
      Attendance attendance = AttendanceFixture.arrived(GRACE_DEADLINE);

      // when & then
      assertThatThrownBy(() -> attendance.arrive(GRACE_DEADLINE, INSIDE_LAT, INSIDE_LNG))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.INVALID_STATE);
    }
  }

  @Nested
  @DisplayName("지각 처리 (스케줄러)")
  class MarkLate {

    @Test
    @DisplayName("유예시간 정각에는 아직 지각이 아니다")
    void markLateIfOverdue_atGraceDeadline_staysMoving() {
      // given
      Attendance attendance = AttendanceFixture.moving(GRACE_DEADLINE);

      // when
      boolean changed = attendance.markLateIfOverdue(GRACE_DEADLINE);

      // then
      assertThat(changed).isFalse();
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    }

    @Test
    @DisplayName("유예시간 1초 후에는 LATE가 된다")
    void markLateIfOverdue_oneSecondAfterGrace_becomesLate() {
      // given
      Attendance attendance = AttendanceFixture.moving(GRACE_DEADLINE);

      // when
      boolean changed = attendance.markLateIfOverdue(GRACE_DEADLINE.plusSeconds(1));

      // then
      assertThat(changed).isTrue();
      assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE);
    }
  }
}
