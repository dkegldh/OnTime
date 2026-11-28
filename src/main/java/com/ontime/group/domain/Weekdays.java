package com.ontime.group.domain;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.DayOfWeek;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 정기 모임이 열리는 요일 집합. 하나 이상이고, 중복은 하나로 합친다.
 *
 * <p>DB에는 {@code days_of_week INT} 비트마스크(월=1 … 일=64, 1~127)로 저장한다. ({@link WeekdaysConverter})
 */
public final class Weekdays {

  /** 모든 요일을 고른 비트마스크. 1~이 값만 유효하다. */
  public static final int ALL_BITS = 0b111_1111;

  private final Set<Weekday> days;

  private Weekdays(Set<Weekday> days) {
    this.days = days;
  }

  /** 요일이 없거나 null이 섞여 있으면 {@code VALIDATION_FAILED}. */
  public static Weekdays of(Collection<Weekday> days) {
    if (days == null || days.isEmpty() || days.contains(null)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new Weekdays(EnumSet.copyOf(days));
  }

  /** DB 비트마스크에서 읽는다. 1~127 밖이면 저장된 데이터가 깨진 것이므로 서버 오류로 남긴다. */
  public static Weekdays fromBits(int bits) {
    if (bits < 1 || bits > ALL_BITS) {
      throw new IllegalArgumentException("요일 비트마스크는 1~127이어야 합니다: " + bits);
    }
    EnumSet<Weekday> days = EnumSet.noneOf(Weekday.class);
    for (Weekday day : Weekday.values()) {
      if ((bits & day.bit()) != 0) {
        days.add(day);
      }
    }
    return new Weekdays(days);
  }

  public int toBits() {
    int bits = 0;
    for (Weekday day : days) {
      bits |= day.bit();
    }
    return bits;
  }

  public boolean contains(DayOfWeek dayOfWeek) {
    return days.contains(Weekday.of(dayOfWeek));
  }

  /** 월요일부터 순서대로. */
  public List<Weekday> toList() {
    return List.copyOf(days);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Weekdays other && days.equals(other.days);
  }

  @Override
  public int hashCode() {
    return Objects.hash(days);
  }

  @Override
  public String toString() {
    return days.toString();
  }
}
