package com.ontime.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.user.domain.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 참석 상태 전이 규칙 전체와 시간 경계.
 *
 * <p>기능별 기본 경계(1초 전후)는 {@link AttendanceTest} 에 있다. 여기서는 전이표 전체를 손으로 쓴 표로 고정하고, 1초 단위 테스트가
 * 놓치는 경계(1나노초, 규칙 극단값, KST 날짜를 넘는 약속)를 본다. 기대값은 모두 손으로 계산한 상수다.
 */
class AttendanceTransitionTest {

  private static final double PLACE_LAT = 37.4979;
  private static final double PLACE_LNG = 127.0276;
  private static final Instant CREATED_AT = Instant.parse("2026-11-25T03:00:00Z");

  private static Meeting meeting(LocalDate date, LocalTime time, int grace, int absentAfter) {
    return Meeting.oneTimeOf(
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", PLACE_LAT, PLACE_LNG),
            date,
            time,
            ArrivalRule.of(grace, absentAfter, 200),
            FineRule.money(3000),
            CREATED_AT));
  }

  // 이미 출발/도착한 상태의 행에 미리 넣어 두는 시각. 거절된 뒤 이 값이 덮어써지지 않았는지 본다.
  // 시간 구간과 무관한 고정값이다. "버튼 열리기 전" 행에서는 현실에 없는 조합이지만, 그 행은 모두 상태 때문에 거절되는지만 본다.
  private static final Instant EARLIER_DEPARTURE = Instant.parse("2026-12-04T08:00:00Z");
  private static final Instant EARLIER_ARRIVAL = Instant.parse("2026-12-04T08:30:00Z");

  /**
   * 그 상태에 맞게 시각을 채운 참석 행. MOVING은 출발 시각, ARRIVED/LATE_ARRIVED는 출발·도착 시각을 가진다. (READY, LATE,
   * ABSENT, EXCUSED는 출발하지 않았을 수 있어 비워 둔다)
   */
  private static Attendance attendanceIn(Meeting meeting, AttendanceStatus status) {
    Attendance attendance = Attendance.ready(meeting, User.create("멤버"));
    ReflectionTestUtils.setField(attendance, "status", status);
    if (status == AttendanceStatus.MOVING
        || status == AttendanceStatus.ARRIVED
        || status == AttendanceStatus.LATE_ARRIVED) {
      ReflectionTestUtils.setField(attendance, "departedAt", EARLIER_DEPARTURE);
    }
    if (status == AttendanceStatus.ARRIVED || status == AttendanceStatus.LATE_ARRIVED) {
      ReflectionTestUtils.setField(attendance, "arrivedAt", EARLIER_ARRIVAL);
    }
    return attendance;
  }

  private enum Action {
    DEPART,
    ARRIVE
  }

  private static void act(Attendance attendance, Action action, Instant now) {
    if (action == Action.DEPART) {
      attendance.depart(now);
    } else {
      attendance.arrive(now, PLACE_LAT, PLACE_LNG);
    }
  }

  /**
   * 동작 결과를 검증한다. {@code expected} 가 AttendanceStatus 이름이면 그 상태로 바뀌고 동작한 시각이 기록돼야 하고, ErrorCode
   * 이름이면 그 코드로 거절되고 상태와 시각이 그대로여야 한다.
   */
  private static void assertResult(
      Attendance attendance, Action action, Instant now, String expected) {
    AttendanceStatus before = attendance.getStatus();
    Instant departedBefore = attendance.getDepartedAt();
    Instant arrivedBefore = attendance.getArrivedAt();
    if (isErrorCode(expected)) {
      assertThatThrownBy(() -> act(attendance, action, now))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.valueOf(expected));
      assertThat(attendance.getStatus()).isEqualTo(before);
      assertThat(attendance.getDepartedAt()).isEqualTo(departedBefore);
      assertThat(attendance.getArrivedAt()).isEqualTo(arrivedBefore);
      return;
    }
    act(attendance, action, now);
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.valueOf(expected));
    if (action == Action.DEPART) {
      assertThat(attendance.getDepartedAt()).isEqualTo(now);
      assertThat(attendance.getArrivedAt()).isNull();
    } else {
      assertThat(attendance.getArrivedAt()).isEqualTo(now);
      // 도착은 출발 시각을 건드리지 않는다 (MOVING에서 도착하면 출발 시각이 남는다)
      assertThat(attendance.getDepartedAt()).isEqualTo(departedBefore);
    }
  }

  private static boolean isErrorCode(String name) {
    for (ErrorCode code : ErrorCode.values()) {
      if (code.name().equals(name)) {
        return true;
      }
    }
    return false;
  }

  // ---- 전이표 ----

  @Nested
  @DisplayName("전이표: 7개 상태 × 출발/도착 × 시간 구간")
  class TransitionTable {

    // 약속 2026-12-04 19:00 KST = 10:00Z, 유예 5분, 결석 60분.
    // 구간: 버튼 열리기 전 06:59:59Z / 유예 전 09:30Z / 유예 ~ 결석 10:30Z / 결석 이후 11:30Z
    private final Meeting meeting = meeting(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0), 5, 60);

    @ParameterizedTest(name = "{0} {1} @ {2} → {3}")
    @CsvSource({
      // READY: 출발은 유예 전까지, 도착은 유예 전 ARRIVED / 이후 지각 간주 LATE_ARRIVED / 결석 이후 거절
      "READY, DEPART, 2026-12-04T06:59:59Z, MEETING_NOT_OPEN",
      "READY, DEPART, 2026-12-04T09:30:00Z, MOVING",
      "READY, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "READY, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "READY, ARRIVE, 2026-12-04T06:59:59Z, MEETING_NOT_OPEN",
      "READY, ARRIVE, 2026-12-04T09:30:00Z, ARRIVED",
      "READY, ARRIVE, 2026-12-04T10:30:00Z, LATE_ARRIVED",
      "READY, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      // MOVING: 출발은 항상 거절, 도착은 READY와 같다
      "MOVING, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "MOVING, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "MOVING, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "MOVING, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "MOVING, ARRIVE, 2026-12-04T06:59:59Z, MEETING_NOT_OPEN",
      "MOVING, ARRIVE, 2026-12-04T09:30:00Z, ARRIVED",
      "MOVING, ARRIVE, 2026-12-04T10:30:00Z, LATE_ARRIVED",
      "MOVING, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      // LATE: 출발은 항상 거절, 도착은 결석 전까지 항상 LATE_ARRIVED
      "LATE, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "LATE, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "LATE, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "LATE, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "LATE, ARRIVE, 2026-12-04T06:59:59Z, MEETING_NOT_OPEN",
      "LATE, ARRIVE, 2026-12-04T09:30:00Z, LATE_ARRIVED",
      "LATE, ARRIVE, 2026-12-04T10:30:00Z, LATE_ARRIVED",
      "LATE, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      // 끝난 상태: 시간과 관계없이 모든 버튼 거절 (상태 검사가 시간 검사보다 먼저)
      "ARRIVED, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "ARRIVED, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "ARRIVED, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "ARRIVED, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "ARRIVED, ARRIVE, 2026-12-04T06:59:59Z, INVALID_STATE",
      "ARRIVED, ARRIVE, 2026-12-04T09:30:00Z, INVALID_STATE",
      "ARRIVED, ARRIVE, 2026-12-04T10:30:00Z, INVALID_STATE",
      "ARRIVED, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "LATE_ARRIVED, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, ARRIVE, 2026-12-04T06:59:59Z, INVALID_STATE",
      "LATE_ARRIVED, ARRIVE, 2026-12-04T09:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, ARRIVE, 2026-12-04T10:30:00Z, INVALID_STATE",
      "LATE_ARRIVED, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      "ABSENT, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "ABSENT, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "ABSENT, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "ABSENT, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "ABSENT, ARRIVE, 2026-12-04T06:59:59Z, INVALID_STATE",
      "ABSENT, ARRIVE, 2026-12-04T09:30:00Z, INVALID_STATE",
      "ABSENT, ARRIVE, 2026-12-04T10:30:00Z, INVALID_STATE",
      "ABSENT, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE",
      "EXCUSED, DEPART, 2026-12-04T06:59:59Z, INVALID_STATE",
      "EXCUSED, DEPART, 2026-12-04T09:30:00Z, INVALID_STATE",
      "EXCUSED, DEPART, 2026-12-04T10:30:00Z, INVALID_STATE",
      "EXCUSED, DEPART, 2026-12-04T11:30:00Z, INVALID_STATE",
      "EXCUSED, ARRIVE, 2026-12-04T06:59:59Z, INVALID_STATE",
      "EXCUSED, ARRIVE, 2026-12-04T09:30:00Z, INVALID_STATE",
      "EXCUSED, ARRIVE, 2026-12-04T10:30:00Z, INVALID_STATE",
      "EXCUSED, ARRIVE, 2026-12-04T11:30:00Z, INVALID_STATE"
    })
    @DisplayName("상태, 동작, 시각별 결과가 전이표와 같고, 거절되면 상태와 시각이 그대로다")
    void transition_matchesTable(
        AttendanceStatus status, Action action, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, status);

      // when & then
      assertResult(attendance, action, now, expected);
    }
  }

  // ---- 1초 미만 경계 ----

  @Nested
  @DisplayName("1나노초 경계: 시각을 초/분 단위로 반올림하거나 올림해 비교하면 실패한다")
  class SubSecondBoundary {

    // 약속 10:00Z, 유예 5분, 결석 60분 → 버튼 07:00:00Z, 지각 10:05:00Z, 결석 11:00:00Z
    private final Meeting meeting = meeting(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0), 5, 60);

    @ParameterizedTest(name = "{0} @ {1} → {2}")
    @CsvSource({
      "DEPART, 2026-12-04T06:59:59.999999999Z, MEETING_NOT_OPEN",
      "ARRIVE, 2026-12-04T06:59:59.999999999Z, MEETING_NOT_OPEN",
      "DEPART, 2026-12-04T10:04:59.999999999Z, MOVING",
      "ARRIVE, 2026-12-04T10:04:59.999999999Z, ARRIVED",
      "ARRIVE, 2026-12-04T10:59:59.999999999Z, LATE_ARRIVED"
    })
    @DisplayName("버튼 오픈, 유예 끝, 결석 시각 1나노초 전은 그 경계 이전으로 판정한다")
    void oneNanoBefore_judgedBeforeBoundary(Action action, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, AttendanceStatus.READY);

      // when & then
      assertResult(attendance, action, now, expected);
    }

    @ParameterizedTest(name = "{0} @ {1} → {2}")
    @CsvSource({
      "DEPART, 2026-12-04T10:04:00.000000001Z, MOVING",
      "ARRIVE, 2026-12-04T10:04:00.000000001Z, ARRIVED",
      "ARRIVE, 2026-12-04T10:59:00.000000001Z, LATE_ARRIVED"
    })
    @DisplayName("경계 1분 전 분 안의 시각도 경계 이전이다 (분 단위 올림 비교 방지)")
    void withinMinuteBeforeBoundary_judgedBeforeBoundary(
        Action action, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, AttendanceStatus.READY);

      // when & then
      assertResult(attendance, action, now, expected);
    }
  }

  // ---- 규칙 극단값 ----

  @Nested
  @DisplayName("유예 0분: 유예 끝 = 약속시간")
  class ZeroGrace {

    // 약속 10:00Z, 유예 0분, 결석 60분 → 약속 정각부터 지각
    private final Meeting meeting = meeting(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0), 0, 60);

    @ParameterizedTest(name = "{0} @ {1} → {2}")
    @CsvSource({
      "DEPART, 2026-12-04T09:59:59.999999999Z, MOVING",
      "DEPART, 2026-12-04T10:00:00Z, INVALID_STATE",
      "ARRIVE, 2026-12-04T09:59:59.999999999Z, ARRIVED",
      "ARRIVE, 2026-12-04T10:00:00Z, LATE_ARRIVED"
    })
    @DisplayName("약속시간 정각부터 출발은 거절되고 도착은 LATE_ARRIVED다")
    void atMeetingTime_late(Action action, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, AttendanceStatus.READY);

      // when & then
      assertResult(attendance, action, now, expected);
    }
  }

  @Nested
  @DisplayName("최대 규칙: 유예 60분, 결석 180분")
  class MaxRules {

    // 약속 10:00Z → 지각 11:00:00Z, 결석 13:00:00Z
    private final Meeting meeting =
        meeting(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0), 60, 180);

    @ParameterizedTest(name = "{0} @ {1} → {2}")
    @CsvSource({
      "DEPART, 2026-12-04T10:59:59Z, MOVING",
      "DEPART, 2026-12-04T11:00:00Z, INVALID_STATE",
      "ARRIVE, 2026-12-04T10:59:59Z, ARRIVED",
      "ARRIVE, 2026-12-04T11:00:00Z, LATE_ARRIVED",
      "ARRIVE, 2026-12-04T12:59:59Z, LATE_ARRIVED",
      "ARRIVE, 2026-12-04T13:00:00Z, INVALID_STATE"
    })
    @DisplayName("유예 끝과 결석 시각을 분 단위 규칙 그대로 더해 판정한다")
    void boundaries_useFullMinutes(Action action, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, AttendanceStatus.READY);

      // when & then
      assertResult(attendance, action, now, expected);
    }
  }

  @Nested
  @DisplayName("가장 짧은 지각 구간: 결석 = 유예 + 1분")
  class MinimalLateWindow {

    // 약속 10:00Z, 유예 5분, 결석 6분 → 10:05:00Z ~ 10:05:59Z 만 지각 도착이 가능하다
    private final Meeting meeting = meeting(LocalDate.of(2026, 12, 4), LocalTime.of(19, 0), 5, 6);

    @ParameterizedTest(name = "{0} @ {1} → {2}")
    @CsvSource({
      "READY, 2026-12-04T10:05:00Z, LATE_ARRIVED",
      "READY, 2026-12-04T10:05:59.999999999Z, LATE_ARRIVED",
      "READY, 2026-12-04T10:06:00Z, INVALID_STATE",
      "LATE, 2026-12-04T10:05:59Z, LATE_ARRIVED",
      "LATE, 2026-12-04T10:06:00Z, INVALID_STATE"
    })
    @DisplayName("1분짜리 지각 구간 안에서만 LATE_ARRIVED이고, 결석 시각 정각부터 거절된다")
    void lateWindow_oneMinute(AttendanceStatus status, Instant now, String expected) {
      // given
      Attendance attendance = attendanceIn(meeting, status);

      // when & then
      assertResult(attendance, Action.ARRIVE, now, expected);
    }
  }

  // ---- KST 날짜를 넘는 약속 ----

  @Nested
  @DisplayName("KST 날짜를 넘는 약속")
  class CrossingKstDate {

    @Test
    @DisplayName("12/5 00:30 KST 약속은 전날 12/4 15:30Z로 저장된다")
    void afterMidnightKst_meetingAtIsPreviousUtcDay() {
      // when
      Meeting meeting = meeting(LocalDate.of(2026, 12, 5), LocalTime.of(0, 30), 5, 60);

      // then
      assertThat(meeting.getMeetingAt()).isEqualTo(Instant.parse("2026-12-04T15:30:00Z"));
    }

    @ParameterizedTest(name = "출발 @ {0} → {1}")
    @CsvSource({"2026-12-04T12:29:59Z, MEETING_NOT_OPEN", "2026-12-04T12:30:00Z, MOVING"})
    @DisplayName("12/5 00:30 KST 약속의 버튼은 전날 12:30Z(21:30 KST) 정각부터 열린다")
    void afterMidnightKst_buttonOpensPreviousDay(Instant now, String expected) {
      // given
      Meeting meeting = meeting(LocalDate.of(2026, 12, 5), LocalTime.of(0, 30), 5, 60);

      // when & then
      assertResult(attendanceIn(meeting, AttendanceStatus.READY), Action.DEPART, now, expected);
    }

    @ParameterizedTest(name = "도착 @ {0} → {1}")
    @CsvSource({
      "2026-12-04T15:29:59Z, ARRIVED",
      "2026-12-04T15:30:00Z, LATE_ARRIVED",
      "2026-12-04T17:29:59Z, LATE_ARRIVED",
      "2026-12-04T17:30:00Z, INVALID_STATE"
    })
    @DisplayName("12/4 23:30 KST 약속(14:30Z), 유예 60분, 결석 180분은 유예 끝(00:30 KST)과 결석(02:30 KST)이 다음 날로 넘어간다")
    void lateNightKst_boundariesCrossMidnight(Instant now, String expected) {
      // given
      Meeting meeting = meeting(LocalDate.of(2026, 12, 4), LocalTime.of(23, 30), 60, 180);

      // when & then
      assertThat(meeting.getMeetingAt()).isEqualTo(Instant.parse("2026-12-04T14:30:00Z"));
      assertResult(attendanceIn(meeting, AttendanceStatus.READY), Action.ARRIVE, now, expected);
    }

    @ParameterizedTest(name = "출발 @ {0} → {1}")
    @CsvSource({
      "2026-12-31T11:59:59Z, MEETING_NOT_OPEN",
      "2026-12-31T12:00:00Z, MOVING",
      "2026-12-31T15:04:59Z, MOVING",
      "2026-12-31T15:05:00Z, INVALID_STATE"
    })
    @DisplayName("2027-01-01 00:00 KST 약속(2026-12-31T15:00Z)은 연도를 넘어도 같은 규칙으로 판정한다")
    void newYearKst_crossesYear(Instant now, String expected) {
      // given
      Meeting meeting = meeting(LocalDate.of(2027, 1, 1), LocalTime.of(0, 0), 5, 60);

      // when & then
      assertThat(meeting.getMeetingAt()).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
      assertResult(attendanceIn(meeting, AttendanceStatus.READY), Action.DEPART, now, expected);
    }
  }
}
