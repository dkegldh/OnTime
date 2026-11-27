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
import org.junit.jupiter.params.provider.CsvSource;
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

  // ---- 도착 ----

  // 약속 장소 37.4979, 127.0276, 반경 200m. 결석 판정은 약속 + 60분 = 11:00:00Z 정각부터.
  // 같은 경도에서 위도 0.00179도 북쪽 = 199.04m(안), 0.00181도 = 201.26m(밖).
  // 같은 위도에서 경도 0.00226도 동쪽 = 199.38m(안), 0.00228도 = 201.14m(밖). (위도 37.5도의 cos 0.793 반영)
  private static final double PLACE_LAT = 37.4979;
  private static final double PLACE_LNG = 127.0276;
  private static final double LAT_199M = PLACE_LAT + 0.00179;
  private static final double LAT_201M = PLACE_LAT + 0.00181;
  private static final Instant ABSENT_FROM = Instant.parse("2026-12-04T11:00:00Z");
  private static final Instant IN_TIME = Instant.parse("2026-12-04T09:55:00Z");

  private static Attendance attendanceIn(AttendanceStatus status) {
    Attendance attendance = readyAttendance();
    ReflectionTestUtils.setField(attendance, "status", status);
    return attendance;
  }

  private static void assertNotArrived(Attendance attendance, AttendanceStatus status) {
    assertThat(attendance.getStatus()).isEqualTo(status);
    assertThat(attendance.getArrivedAt()).isNull();
  }

  private static void assertArriveRejected(
      Attendance attendance, Instant now, double lat, double lng, ErrorCode errorCode) {
    assertThatThrownBy(() -> attendance.arrive(now, lat, lng))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(errorCode);
  }

  @Test
  @DisplayName("READY에서 유예 전에 반경 안에서 도착하면 ARRIVED가 되고 도착 시각은 넘겨받은 서버 시각이다")
  void arrive_readyInTime_becomesArrivedWithArrivedAt() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.arrive(IN_TIME, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(IN_TIME);
    assertThat(attendance.getDepartedAt()).isNull();
  }

  @Test
  @DisplayName("MOVING에서 유예 전에 도착하면 ARRIVED가 되고 출발 시각은 그대로다")
  void arrive_movingInTime_becomesArrivedKeepingDepartedAt() {
    // given
    Attendance attendance = readyAttendance();
    Instant departedAt = Instant.parse("2026-12-04T09:00:00Z");
    attendance.depart(departedAt);

    // when
    attendance.arrive(IN_TIME, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(IN_TIME);
    assertThat(attendance.getDepartedAt()).isEqualTo(departedAt);
  }

  @Test
  @DisplayName("약속 3시간 전 정각에 도착하면 ARRIVED다")
  void arrive_exactlyThreeHoursBefore_arrived() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.arrive(OPENS_AT, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(OPENS_AT);
  }

  @Test
  @DisplayName("약속 3시간 전 1초 전에 도착하면 MEETING_NOT_OPEN으로 거절되고 상태는 그대로다")
  void arrive_oneSecondBeforeOpen_rejectedWithMeetingNotOpen() {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertArriveRejected(
        attendance, OPENS_AT.minusSeconds(1), PLACE_LAT, PLACE_LNG, ErrorCode.MEETING_NOT_OPEN);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceStatus.class, names = {"READY", "MOVING"})
  @DisplayName("유예 끝나기 1초 전에 도착하면 ARRIVED다")
  void arrive_oneSecondBeforeGraceEnds_arrived(AttendanceStatus status) {
    // given
    Attendance attendance = attendanceIn(status);

    // when
    attendance.arrive(LATE_FROM.minusSeconds(1), PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(LATE_FROM.minusSeconds(1));
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceStatus.class, names = {"READY", "MOVING"})
  @DisplayName("약속시간 + 유예 정각에 도착하면 스케줄러가 돌지 않아 READY/MOVING이어도 지각으로 보고 LATE_ARRIVED다")
  void arrive_atGraceEnd_lateArrived(AttendanceStatus status) {
    // given
    Attendance attendance = attendanceIn(status);

    // when
    attendance.arrive(LATE_FROM, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(LATE_FROM);
  }

  @Test
  @DisplayName("유예 끝나고 1초 뒤에 도착하면 LATE_ARRIVED다")
  void arrive_oneSecondAfterGraceEnds_lateArrived() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.arrive(LATE_FROM.plusSeconds(1), PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
  }

  @Test
  @DisplayName("스케줄러가 LATE로 바꾼 뒤 도착하면 LATE_ARRIVED다")
  void arrive_late_lateArrived() {
    // given
    Attendance attendance = attendanceIn(AttendanceStatus.LATE);
    Instant now = Instant.parse("2026-12-04T10:30:00Z");

    // when
    attendance.arrive(now, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(now);
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceStatus.class, names = {"READY", "MOVING", "LATE"})
  @DisplayName("결석 판정 시각 1초 전에 도착하면 LATE_ARRIVED다")
  void arrive_oneSecondBeforeAbsent_lateArrived(AttendanceStatus status) {
    // given
    Attendance attendance = attendanceIn(status);

    // when
    attendance.arrive(ABSENT_FROM.minusSeconds(1), PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
  }

  @ParameterizedTest(name = "{0}, 결석 판정 + {1}초")
  @CsvSource({"READY, 0", "MOVING, 0", "LATE, 0", "LATE, 1"})
  @DisplayName("결석 판정 시각 정각부터는 스케줄러가 돌지 않았어도 INVALID_STATE로 거절되고 상태는 그대로다")
  void arrive_atOrAfterAbsent_rejectedWithInvalidState(AttendanceStatus status, long secondsAfter) {
    // given
    Attendance attendance = attendanceIn(status);

    // when & then
    assertArriveRejected(
        attendance,
        ABSENT_FROM.plusSeconds(secondsAfter),
        PLACE_LAT,
        PLACE_LNG,
        ErrorCode.INVALID_STATE);
    assertNotArrived(attendance, status);
  }

  @ParameterizedTest
  @EnumSource(
      value = AttendanceStatus.class,
      names = {"ARRIVED", "LATE_ARRIVED", "ABSENT", "EXCUSED"})
  @DisplayName("이미 도착했거나 결석/빠지기 상태면 반경 안, 유예 전이어도 INVALID_STATE로 거절되고 상태와 도착 시각은 그대로다")
  void arrive_notArrivable_rejectedWithInvalidState(AttendanceStatus status) {
    // given
    Attendance attendance = attendanceIn(status);
    Instant earlierArrival = Instant.parse("2026-12-04T09:40:00Z");
    ReflectionTestUtils.setField(attendance, "arrivedAt", earlierArrival);

    // when & then
    assertArriveRejected(attendance, IN_TIME, PLACE_LAT, PLACE_LNG, ErrorCode.INVALID_STATE);
    assertThat(attendance.getStatus()).isEqualTo(status);
    assertThat(attendance.getArrivedAt()).isEqualTo(earlierArrival);
  }

  @Test
  @DisplayName("북쪽으로 199m 떨어진 곳에서는 도착할 수 있다 (반경 200m 안)")
  void arrive_199mNorth_arrived() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.arrive(IN_TIME, LAT_199M, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
  }

  @Test
  @DisplayName("북쪽으로 201m 떨어진 곳에서 도착하면 ARRIVE_TOO_FAR로 거절되고 상태는 그대로다")
  void arrive_201mNorth_rejectedWithArriveTooFar() {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertArriveRejected(attendance, IN_TIME, LAT_201M, PLACE_LNG, ErrorCode.ARRIVE_TOO_FAR);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }

  @Test
  @DisplayName("동쪽으로 199m 떨어진 곳에서는 도착할 수 있다 (경도 간격을 위도로 줄여 계산)")
  void arrive_199mEast_arrived() {
    // given
    Attendance attendance = readyAttendance();

    // when
    attendance.arrive(IN_TIME, PLACE_LAT, PLACE_LNG + 0.00226);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
  }

  @Test
  @DisplayName("동쪽으로 201m 떨어진 곳에서 도착하면 ARRIVE_TOO_FAR다 (경도 0.00228도는 적도 기준이면 254m지만 여기선 201m)")
  void arrive_201mEast_rejectedWithArriveTooFar() {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertArriveRejected(
        attendance, IN_TIME, PLACE_LAT, PLACE_LNG + 0.00228, ErrorCode.ARRIVE_TOO_FAR);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }

  @Test
  @DisplayName("지각 시간에 반경 밖이면 ARRIVE_TOO_FAR로 거절되고 LATE_ARRIVED로 바뀌지 않는다")
  void arrive_lateAndTooFar_rejectedWithArriveTooFar() {
    // given
    Attendance attendance = attendanceIn(AttendanceStatus.MOVING);

    // when & then
    assertArriveRejected(
        attendance, LATE_FROM.plusSeconds(60), LAT_201M, PLACE_LNG, ErrorCode.ARRIVE_TOO_FAR);
    assertNotArrived(attendance, AttendanceStatus.MOVING);
  }

  @Test
  @DisplayName("결석 판정 시각이 지났으면 반경 밖이어도 거리가 아니라 시간 때문에 INVALID_STATE로 거절된다")
  void arrive_afterAbsentAndTooFar_rejectedWithInvalidState() {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertArriveRejected(attendance, ABSENT_FROM, LAT_201M, PLACE_LNG, ErrorCode.INVALID_STATE);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }

  @Test
  @DisplayName("회차를 만든 뒤 그룹 규칙이 바뀌어도 도착은 회차에 복사된 장소, 반경, 유예, 결석 시간으로 판정한다")
  void arrive_groupRulesChangedAfterSnapshot_usesMeetingSnapshot() {
    // given: 회차는 강남역, 유예 5분, 결석 60분, 반경 200m로 복사됐고, 그 뒤 그룹만 바뀐다
    MeetingGroup group =
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", PLACE_LAT, PLACE_LNG),
            LocalDate.of(2026, 12, 4),
            LocalTime.of(19, 0),
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            Instant.parse("2026-11-25T03:00:00Z"));
    Meeting meeting = Meeting.oneTimeOf(group);
    ReflectionTestUtils.setField(group, "place", Place.of("서울역", 37.5547, 126.9707));
    ReflectionTestUtils.setField(group, "arrivalRule", ArrivalRule.of(0, 1, 50));
    Attendance attendance = Attendance.ready(meeting, User.create("멤버"));

    // when: 그룹 기준이면 장소가 멀고, 반경 50m 밖이고, 결석 시각(10:01)도 지났다
    Instant now = LATE_FROM.minusSeconds(1);
    attendance.arrive(now, LAT_199M, PLACE_LNG);

    // then: 회차 기준이면 반경 안, 유예 전이라 정상 도착
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("반경이 50m인 회차에서는 199m 떨어진 곳에서 도착할 수 없다 (회차의 반경을 쓴다)")
  void arrive_usesMeetingRadius() {
    // given
    Meeting smallRadius =
        Meeting.oneTimeOf(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", PLACE_LAT, PLACE_LNG),
                LocalDate.of(2026, 12, 4),
                LocalTime.of(19, 0),
                ArrivalRule.of(5, 60, 50),
                FineRule.money(3000),
                Instant.parse("2026-11-25T03:00:00Z")));
    Attendance attendance = Attendance.ready(smallRadius, User.create("멤버"));

    // when & then
    assertArriveRejected(attendance, IN_TIME, LAT_199M, PLACE_LNG, ErrorCode.ARRIVE_TOO_FAR);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }

  @Test
  @DisplayName("거리가 반경과 정확히 같으면 도착으로 인정한다 (반경 0인 회차에서 장소 좌표 그대로 도착: 거리 0 = 반경 0)")
  void arrive_distanceEqualsRadius_arrived() {
    // given: 실수 좌표로 정확히 200.000m를 만들 수 없어서, 거리와 반경이 모두 정확히 0인 경우로 경계를 고정한다
    Meeting zeroRadius =
        Meeting.oneTimeOf(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", PLACE_LAT, PLACE_LNG),
                LocalDate.of(2026, 12, 4),
                LocalTime.of(19, 0),
                ArrivalRule.of(5, 60, 0),
                FineRule.money(3000),
                Instant.parse("2026-11-25T03:00:00Z")));
    Attendance attendance = Attendance.ready(zeroRadius, User.create("멤버"));

    // when
    attendance.arrive(IN_TIME, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
  }

  @Test
  @DisplayName("LATE 상태면 유예 전 시각이어도 LATE_ARRIVED다 (LATE는 언제나 지각 도착)")
  void arrive_lateBeforeGraceEnd_lateArrived() {
    // given
    Attendance attendance = attendanceIn(AttendanceStatus.LATE);

    // when
    attendance.arrive(IN_TIME, PLACE_LAT, PLACE_LNG);

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.LATE_ARRIVED);
    assertThat(attendance.getArrivedAt()).isEqualTo(IN_TIME);
  }

  @Test
  @DisplayName("버튼이 열리기 전이면 반경 밖이어도 거리가 아니라 시간 때문에 MEETING_NOT_OPEN으로 거절된다")
  void arrive_beforeOpenAndTooFar_rejectedWithMeetingNotOpen() {
    // given
    Attendance attendance = readyAttendance();

    // when & then
    assertArriveRejected(
        attendance, OPENS_AT.minusSeconds(1), LAT_201M, PLACE_LNG, ErrorCode.MEETING_NOT_OPEN);
    assertNotArrived(attendance, AttendanceStatus.READY);
  }
}
