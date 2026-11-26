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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class AttendanceTest {

  // 약속 2026-12-04 19:00 KST = 10:00Z, 유예 5분.
  // 버튼은 3시간 전인 07:00:00Z 부터 열리고, 10:05:00Z 정각부터 지각이다. (기대값은 손으로 계산한 상수)
  private static final Instant OPENS_AT = Instant.parse("2026-12-04T07:00:00Z");
  private static final Instant LATE_FROM = Instant.parse("2026-12-04T10:05:00Z");

  private static Meeting meeting() {
    return Meeting.oneTimeOf(
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", 37.4979, 127.0276),
            LocalDate.of(2026, 12, 4),
            LocalTime.of(19, 0),
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            Instant.parse("2026-11-25T03:00:00Z")));
  }

  @Test
  @DisplayName("회차에 등록된 멤버는 출발/도착 시각 없이 READY로 시작한다")
  void ready_startsAsReadyWithoutDepartureOrArrival() {
    // when
    Attendance attendance = Attendance.ready(meeting(), User.create("멤버"));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.READY);
    assertThat(attendance.getDepartedAt()).isNull();
    assertThat(attendance.getArrivedAt()).isNull();
  }

  private static Attendance readyAttendance() {
    return Attendance.ready(meeting(), User.create("멤버"));
  }

  private static void assertUnchangedReady(Attendance attendance) {
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.READY);
    assertThat(attendance.getDepartedAt()).isNull();
  }

  // ---- 출발 ----

  @Test
  @DisplayName("READY에서 출발하면 MOVING이 되고 출발 시각은 넘겨받은 서버 시각이다")
  void depart_ready_becomesMovingWithDepartedAt() {
    // given
    Attendance attendance = readyAttendance();
    Instant now = Instant.parse("2026-12-04T09:30:00Z");

    // when
    attendance.depart(now);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(attendance.getDepartedAt()).isEqualTo(now);
    assertThat(attendance.getArrivedAt()).isNull();
  }

  @Test
  @DisplayName("약속 3시간 전 정각에는 출발할 수 있다")
  void depart_exactlyThreeHoursBefore_allowed() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.depart(OPENS_AT);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(attendance.getDepartedAt()).isEqualTo(OPENS_AT);
  }

  @Test
  @DisplayName("약속 3시간 전 1초 후에는 출발할 수 있다")
  void depart_oneSecondAfterOpen_allowed() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.depart(OPENS_AT.plusSeconds(1));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
  }

  @ParameterizedTest(name = "버튼 열리기 {0}초 전")
  @ValueSource(longs = {1, 3600})
  @DisplayName("약속 3시간 전보다 이르면 MEETING_NOT_OPEN으로 거절되고 상태는 그대로다")
  void depart_beforeOpen_rejectedWithMeetingNotOpen(long secondsBefore) {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertThatThrownBy(() -> attendance.depart(OPENS_AT.minusSeconds(secondsBefore)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_NOT_OPEN);
    assertUnchangedReady(attendance);
  }

  @Test
  @DisplayName("약속시간 정각(유예 중)에는 출발할 수 있다")
  void depart_atMeetingTimeWithinGrace_allowed() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.depart(Instant.parse("2026-12-04T10:00:00Z"));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
  }

  @Test
  @DisplayName("유예 끝나기 1초 전에는 출발할 수 있다")
  void depart_oneSecondBeforeGraceEnds_allowed() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.depart(LATE_FROM.minusSeconds(1));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(attendance.getDepartedAt()).isEqualTo(LATE_FROM.minusSeconds(1));
  }

  @ParameterizedTest(name = "유예 끝 + {0}초")
  @ValueSource(longs = {0, 1})
  @DisplayName("약속시간 + 유예 정각부터는 READY여도 INVALID_STATE로 거절되고 상태는 그대로다")
  void depart_atOrAfterGraceEnds_rejectedWithInvalidState(long secondsAfter) {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertThatThrownBy(() -> attendance.depart(LATE_FROM.plusSeconds(secondsAfter)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertUnchangedReady(attendance);
  }

  @Test
  @DisplayName("유예시간이 다른 회차는 그 회차의 유예시간으로 출발 마감을 판단한다")
  void depart_usesMeetingGraceMinutes() {
    // given: 유예 0분이면 약속시간 정각부터 지각이다
    Meeting noGrace =
        Meeting.oneTimeOf(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", 37.4979, 127.0276),
                LocalDate.of(2026, 12, 4),
                LocalTime.of(19, 0),
                ArrivalRule.of(0, 60, 200),
                FineRule.money(3000),
                Instant.parse("2026-11-25T03:00:00Z")));
    Attendance attendance = Attendance.ready(noGrace, User.create("멤버"));

    // when & then
    assertThatThrownBy(() -> attendance.depart(Instant.parse("2026-12-04T10:00:00Z")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertUnchangedReady(attendance);
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("READY가 아닌 상태에서는 버튼이 열린 시간이어도 INVALID_STATE로 거절되고 상태와 출발 시각은 그대로다")
  void depart_notReady_rejectedWithInvalidState(AttendanceStatus status) {
    // given
    Attendance attendance = readyAttendance();
    Instant earlierDeparture = Instant.parse("2026-12-04T08:00:00Z");
    ReflectionTestUtils.setField(attendance, "status", status);
    ReflectionTestUtils.setField(attendance, "departedAt", earlierDeparture);

    // when & then
    assertThatThrownBy(() -> attendance.depart(Instant.parse("2026-12-04T09:30:00Z")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(attendance.getStatus()).isEqualTo(status);
    assertThat(attendance.getDepartedAt()).isEqualTo(earlierDeparture);
  }

  @Test
  @DisplayName("빠지기 승인된(EXCUSED) 멤버는 버튼이 열리기 전이어도 시간이 아니라 상태 때문에 INVALID_STATE로 거절된다")
  void depart_excusedBeforeOpen_rejectedWithInvalidState() {
    // given
    Attendance attendance = readyAttendance();
    ReflectionTestUtils.setField(attendance, "status", AttendanceStatus.EXCUSED);

    // when & then
    assertThatThrownBy(() -> attendance.depart(OPENS_AT.minusSeconds(3600)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE);
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.EXCUSED);
    assertThat(attendance.getDepartedAt()).isNull();
  }

  @Test
  @DisplayName("회차를 만든 뒤 그룹 유예시간이 바뀌어도 출발 마감은 회차에 복사된 유예시간으로 판단한다")
  void depart_groupGraceChangedAfterSnapshot_usesMeetingSnapshot() {
    // given: 회차는 유예 5분으로 복사됐고, 그 뒤 그룹 유예만 0분으로 바뀐다
    MeetingGroup group =
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", 37.4979, 127.0276),
            LocalDate.of(2026, 12, 4),
            LocalTime.of(19, 0),
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            Instant.parse("2026-11-25T03:00:00Z"));
    Meeting meeting = Meeting.oneTimeOf(group);
    ReflectionTestUtils.setField(group, "arrivalRule", ArrivalRule.of(0, 60, 200));
    Attendance attendance = Attendance.ready(meeting, User.create("멤버"));

    // when: 그룹 기준(유예 0분)이면 마감, 회차 기준(유예 5분)이면 유예 중인 약속시간 정각
    attendance.depart(Instant.parse("2026-12-04T10:00:00Z"));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.MOVING);
  }
}
