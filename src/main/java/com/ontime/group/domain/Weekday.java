package com.ontime.group.domain;

import java.time.DayOfWeek;

/**
 * 정기 모임 요일. API에서는 {@code "MON"}, {@code "WED"} 처럼 세 글자로 주고받는다.
 *
 * <p>DB에는 {@link Weekdays} 비트마스크로 저장하며, 비트 위치는 선언 순서(월=1, 화=2, … 일=64)다. 순서를 바꾸면 저장된
 * 값의 의미가 바뀌므로 바꾸지 않는다.
 */
public enum Weekday {
  MON(DayOfWeek.MONDAY),
  TUE(DayOfWeek.TUESDAY),
  WED(DayOfWeek.WEDNESDAY),
  THU(DayOfWeek.THURSDAY),
  FRI(DayOfWeek.FRIDAY),
  SAT(DayOfWeek.SATURDAY),
  SUN(DayOfWeek.SUNDAY);

  private final DayOfWeek dayOfWeek;

  Weekday(DayOfWeek dayOfWeek) {
    this.dayOfWeek = dayOfWeek;
  }

  public static Weekday of(DayOfWeek dayOfWeek) {
    return values()[dayOfWeek.getValue() - 1];
  }

  public DayOfWeek toDayOfWeek() {
    return dayOfWeek;
  }

  int bit() {
    return 1 << ordinal();
  }
}
