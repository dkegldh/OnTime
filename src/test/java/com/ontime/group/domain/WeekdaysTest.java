package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeekdaysTest {

  @Test
  @DisplayName("비트마스크는 월=1부터 일=64까지 요일마다 한 비트다")
  void toBits_eachDay_usesOneBitFromMondayToSunday() {
    assertThat(Weekdays.of(List.of(Weekday.MON)).toBits()).isEqualTo(1);
    assertThat(Weekdays.of(List.of(Weekday.TUE)).toBits()).isEqualTo(2);
    assertThat(Weekdays.of(List.of(Weekday.WED)).toBits()).isEqualTo(4);
    assertThat(Weekdays.of(List.of(Weekday.THU)).toBits()).isEqualTo(8);
    assertThat(Weekdays.of(List.of(Weekday.FRI)).toBits()).isEqualTo(16);
    assertThat(Weekdays.of(List.of(Weekday.SAT)).toBits()).isEqualTo(32);
    assertThat(Weekdays.of(List.of(Weekday.SUN)).toBits()).isEqualTo(64);
    assertThat(Weekdays.of(Arrays.asList(Weekday.values())).toBits()).isEqualTo(127);
  }

  @Test
  @DisplayName("같은 요일을 여러 번 보내면 하나로 합치고, 목록은 월요일부터 순서대로다")
  void of_duplicatedAndUnordered_mergedAndSortedFromMonday() {
    // when
    Weekdays weekdays = Weekdays.of(List.of(Weekday.SUN, Weekday.WED, Weekday.MON, Weekday.WED));

    // then
    assertThat(weekdays.toList()).containsExactly(Weekday.MON, Weekday.WED, Weekday.SUN);
    assertThat(weekdays.toBits()).isEqualTo(1 + 4 + 64);
  }

  @Test
  @DisplayName("비트마스크에서 읽으면 같은 요일 집합이 된다")
  void fromBits_storedMask_restoresSameDays() {
    // when: 월(1) + 금(16) + 토(32)
    Weekdays weekdays = Weekdays.fromBits(49);

    // then
    assertThat(weekdays.toList()).containsExactly(Weekday.MON, Weekday.FRI, Weekday.SAT);
    assertThat(weekdays.contains(DayOfWeek.FRIDAY)).isTrue();
    assertThat(weekdays.contains(DayOfWeek.THURSDAY)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 128, -1})
  @DisplayName("1~127 밖의 비트마스크는 깨진 데이터로 보고 거절한다")
  void fromBits_outOfRange_rejected(int bits) {
    assertThatThrownBy(() -> Weekdays.fromBits(bits)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("요일이 하나도 없으면 VALIDATION_FAILED로 거절된다")
  void of_empty_rejectedWithValidationFailed() {
    assertThatThrownBy(() -> Weekdays.of(List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("요일 목록에 null이 섞여 있으면 VALIDATION_FAILED로 거절된다")
  void of_containsNull_rejectedWithValidationFailed() {
    assertThatThrownBy(() -> Weekdays.of(Arrays.asList(Weekday.MON, null)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("Weekday는 java.time 요일과 같은 요일로 대응한다")
  void weekday_of_matchesJavaTimeDayOfWeek() {
    assertThat(Weekday.of(DayOfWeek.MONDAY)).isEqualTo(Weekday.MON);
    assertThat(Weekday.of(DayOfWeek.SUNDAY)).isEqualTo(Weekday.SUN);
    assertThat(Weekday.WED.toDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
  }
}
