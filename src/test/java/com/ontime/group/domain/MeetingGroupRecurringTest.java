package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정기 모임 생성 규칙과 회차 일시 계산. 기대 일시는 손으로 계산한 상수다. */
class MeetingGroupRecurringTest {

  // 서버 시각 2026-11-25(수) 12:00 KST
  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
  private static final LocalTime SEVEN_PM = LocalTime.of(19, 0);

  private static MeetingGroup create(
      LocalDate start, LocalDate end, List<Weekday> days, LocalTime time, Instant now) {
    return MeetingGroup.createRecurring(
        "스터디",
        Place.of("강남역", 37.4979, 127.0276),
        start,
        end,
        Weekdays.of(days),
        time,
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        now);
  }

  private static void assertRejected(Runnable creation, ErrorCode expected) {
    assertThatThrownBy(creation::run)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("기간 안의 고른 요일마다 만나는 시각(KST)에 회차가 열린다")
  void createRecurring_mondayAndWednesday_meetingTimesOnThoseDays() {
    // given: 2026-11-30(월) ~ 2026-12-13(일), 월·수 19:00 KST
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 30),
            LocalDate.of(2026, 12, 13),
            List.of(Weekday.MON, Weekday.WED),
            SEVEN_PM,
            NOW);

    // when
    List<Instant> times = group.recurringMeetingTimesAfter(NOW);

    // then: 19:00 KST = 10:00 UTC
    assertThat(times)
        .containsExactly(
            Instant.parse("2026-11-30T10:00:00Z"),
            Instant.parse("2026-12-02T10:00:00Z"),
            Instant.parse("2026-12-07T10:00:00Z"),
            Instant.parse("2026-12-09T10:00:00Z"));
    assertThat(group.getType()).isEqualTo(GroupType.RECURRING);
    assertThat(group.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 30));
    assertThat(group.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 13));
    assertThat(group.getDaysOfWeek().toList()).containsExactly(Weekday.MON, Weekday.WED);
  }

  @Test
  @DisplayName("시작일과 종료일 당일도 기간에 들어간다")
  void createRecurring_startAndEndOnChosenDay_bothIncluded() {
    // given: 2026-11-30(월) ~ 2026-12-07(월), 월요일
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 7), List.of(Weekday.MON), SEVEN_PM,
            NOW);

    // when & then
    assertThat(group.recurringMeetingTimesAfter(NOW))
        .containsExactly(
            Instant.parse("2026-11-30T10:00:00Z"), Instant.parse("2026-12-07T10:00:00Z"));
  }

  @Test
  @DisplayName("시작일과 종료일이 같은 하루짜리 기간도 만들 수 있다")
  void createRecurring_singleDayPeriod_oneMeeting() {
    // when
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 30), List.of(Weekday.MON), SEVEN_PM,
            NOW);

    // then
    assertThat(group.recurringMeetingTimesAfter(NOW))
        .containsExactly(Instant.parse("2026-11-30T10:00:00Z"));
  }

  @Test
  @DisplayName("요일은 UTC가 아닌 KST 날짜로 판단한다: 월요일 01:00 KST는 UTC로 일요일이다")
  void createRecurring_earlyMorningKst_usesKstDayOfWeek() {
    // given: 2026-11-29(일) ~ 2026-11-30(월), 월요일 01:00 KST = 2026-11-29T16:00Z (UTC로는 일요일)
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 29),
            LocalDate.of(2026, 11, 30),
            List.of(Weekday.MON),
            LocalTime.of(1, 0),
            NOW);

    // when & then
    assertThat(group.recurringMeetingTimesAfter(NOW))
        .containsExactly(Instant.parse("2026-11-29T16:00:00Z"));
  }

  @Test
  @DisplayName("기간이 시작일·종료일 포함 90일이면 만들어지고, 매일 고르면 회차가 90개다")
  void createRecurring_ninetyDaysEveryDay_ninetyMeetings() {
    // given: 2026-11-30 ~ 2027-02-27 (양 끝 포함 90일)
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 30),
            LocalDate.of(2027, 2, 27),
            Arrays.asList(Weekday.values()),
            SEVEN_PM,
            NOW);

    // when
    List<Instant> times = group.recurringMeetingTimesAfter(NOW);

    // then
    assertThat(times).hasSize(90);
    assertThat(times.get(0)).isEqualTo(Instant.parse("2026-11-30T10:00:00Z"));
    assertThat(times.get(89)).isEqualTo(Instant.parse("2027-02-27T10:00:00Z"));
  }

  @Test
  @DisplayName("기간이 91일이면 VALIDATION_FAILED로 거절된다")
  void createRecurring_ninetyOneDays_rejectedWithValidationFailed() {
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 11, 30),
                LocalDate.of(2027, 2, 28),
                List.of(Weekday.MON),
                SEVEN_PM,
                NOW),
        ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("종료일이 시작일보다 앞서면 VALIDATION_FAILED로 거절된다")
  void createRecurring_endBeforeStart_rejectedWithValidationFailed() {
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 11, 30),
                List.of(Weekday.MON),
                SEVEN_PM,
                NOW),
        ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("시작일이 오늘(KST)이면 만들어진다")
  void createRecurring_startToday_created() {
    // given: 오늘 2026-11-25(수), 수요일 19:00
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25), List.of(Weekday.WED), SEVEN_PM,
            NOW);

    // then
    assertThat(group.recurringMeetingTimesAfter(NOW))
        .containsExactly(Instant.parse("2026-11-25T10:00:00Z"));
  }

  @Test
  @DisplayName("시작일이 어제(KST)면 MEETING_TIME_IN_PAST로 거절된다")
  void createRecurring_startYesterday_rejectedAsInPast() {
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 12, 31),
                List.of(Weekday.MON),
                SEVEN_PM,
                NOW),
        ErrorCode.MEETING_TIME_IN_PAST);
  }

  @Test
  @DisplayName("오늘은 KST 날짜로 판단한다: 00:00 KST(UTC 전날 15:00)에 전날 시작이면 거절된다")
  void createRecurring_kstMidnight_previousDayIsPast() {
    // given: 2026-11-24T15:00Z = 2026-11-25 00:00 KST. UTC 날짜로 보면 아직 11-24라 통과해 버린다.
    Instant kstMidnight = Instant.parse("2026-11-24T15:00:00Z");

    // when & then
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 12, 31),
                List.of(Weekday.MON),
                SEVEN_PM,
                kstMidnight),
        ErrorCode.MEETING_TIME_IN_PAST);
  }

  @Test
  @DisplayName("오늘 회차의 시각이 서버 시각과 같으면 그 회차는 빼고 다음 회차부터 만든다")
  void createRecurring_todayMeetingAtNow_excludesToday() {
    // given: 오늘 2026-11-25(수) 12:00 KST = 서버 시각. 수요일, 다음 주 수요일까지
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 25),
            LocalDate.of(2026, 12, 2),
            List.of(Weekday.WED),
            LocalTime.of(12, 0),
            NOW);

    // when & then
    assertThat(group.recurringMeetingTimesAfter(NOW))
        .containsExactly(Instant.parse("2026-12-02T03:00:00Z"));
  }

  @Test
  @DisplayName("오늘 회차의 시각이 서버 시각 1초 뒤면 오늘 회차도 만든다")
  void createRecurring_todayMeetingOneSecondAfterNow_includesToday() {
    // given: 서버 시각 11:59:59 KST, 회차 12:00 KST
    Instant now = Instant.parse("2026-11-25T02:59:59Z");
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 25),
            LocalDate.of(2026, 12, 2),
            List.of(Weekday.WED),
            LocalTime.of(12, 0),
            now);

    // when & then
    assertThat(group.recurringMeetingTimesAfter(now))
        .containsExactly(
            Instant.parse("2026-11-25T03:00:00Z"), Instant.parse("2026-12-02T03:00:00Z"));
  }

  @Test
  @DisplayName("유일한 회차가 오늘 이미 지난 시각이면 NO_MEETING_IN_PERIOD로 거절된다")
  void createRecurring_onlyMeetingAlreadyStarted_rejectedWithNoMeeting() {
    // given: 오늘 하루, 수요일 12:00 KST = 서버 시각
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 25),
                List.of(Weekday.WED),
                LocalTime.of(12, 0),
                NOW),
        ErrorCode.NO_MEETING_IN_PERIOD);
  }

  @Test
  @DisplayName("기간 안에 고른 요일이 하루도 없으면 NO_MEETING_IN_PERIOD로 거절된다")
  void createRecurring_noChosenDayInPeriod_rejectedWithNoMeeting() {
    // given: 2026-11-30(월) ~ 2026-12-01(화), 금요일
    assertRejected(
        () ->
            create(
                LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 12, 1),
                List.of(Weekday.FRI),
                SEVEN_PM,
                NOW),
        ErrorCode.NO_MEETING_IN_PERIOD);
  }

  @Test
  @DisplayName("회차 일시는 넘긴 시각 뒤의 것만 돌려준다: 기간 중간 시각이면 남은 회차만")
  void recurringMeetingTimesAfter_midPeriod_returnsOnlyRemaining() {
    // given
    MeetingGroup group =
        create(
            LocalDate.of(2026, 11, 30),
            LocalDate.of(2026, 12, 13),
            List.of(Weekday.MON, Weekday.WED),
            SEVEN_PM,
            NOW);

    // when: 두 번째 회차(12-02 19:00 KST) 정각
    List<Instant> times = group.recurringMeetingTimesAfter(Instant.parse("2026-12-02T10:00:00Z"));

    // then
    assertThat(times)
        .containsExactly(
            Instant.parse("2026-12-07T10:00:00Z"), Instant.parse("2026-12-09T10:00:00Z"));
  }

  @Test
  @DisplayName("일회성 그룹은 기간과 요일이 없다")
  void createOneTime_hasNoPeriodOrDays() {
    // when
    MeetingGroup group =
        MeetingGroup.createOneTime(
            "약속",
            Place.of("강남역", 37.4979, 127.0276),
            LocalDate.of(2026, 12, 4),
            SEVEN_PM,
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            NOW);

    // then
    assertThat(group.getEndDate()).isNull();
    assertThat(group.getDaysOfWeek()).isNull();
  }
}
