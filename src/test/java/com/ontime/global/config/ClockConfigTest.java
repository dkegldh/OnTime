package com.ontime.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

  @Test
  @DisplayName("서버 Clock은 나노초 시각을 마이크로초로 내림한다 (DB 반올림으로 판정 시각과 저장 시각이 갈라지지 않게)")
  void microsecondClock_truncatesNanosDown() {
    // given: 유예 끝(10:05:00) 1나노초 전. 반올림하면 정각이 되어 지각 경계를 넘는다
    Clock base = Clock.fixed(Instant.parse("2026-11-28T10:04:59.999999999Z"), ZoneOffset.UTC);

    // when
    Instant now = Instant.now(ClockConfig.microsecondClock(base));

    // then
    assertThat(now).isEqualTo(Instant.parse("2026-11-28T10:04:59.999999Z"));
  }

  @Test
  @DisplayName("이미 마이크로초 단위인 시각은 그대로 둔다")
  void microsecondClock_keepsMicros() {
    // given
    Clock base = Clock.fixed(Instant.parse("2026-11-28T10:04:59.123456Z"), ZoneOffset.UTC);

    // when
    Instant now = Instant.now(ClockConfig.microsecondClock(base));

    // then
    assertThat(now).isEqualTo(Instant.parse("2026-11-28T10:04:59.123456Z"));
  }

  @Test
  @DisplayName("millis()도 예외 없이 내림한 시각의 밀리초를 준다 (JDK 17 Clock.tick의 1ms 미만 / by zero 회귀 방지)")
  void microsecondClock_millisWorks() {
    // given
    Clock clock =
        ClockConfig.microsecondClock(
            Clock.fixed(Instant.parse("2026-11-28T10:04:59.999999999Z"), ZoneOffset.UTC));

    // when
    long millis = clock.millis();

    // then: 2026-11-28T10:04:59.999Z
    assertThat(millis).isEqualTo(1_795_860_299_999L);
    assertThat(millis).isEqualTo(clock.instant().toEpochMilli());
  }

  @Test
  @DisplayName("withZone으로 바꾼 Clock도 마이크로초 내림을 유지한다")
  void microsecondClock_withZone_keepsTruncation() {
    // given
    Clock clock =
        ClockConfig.microsecondClock(
            Clock.fixed(Instant.parse("2026-11-28T10:04:59.999999999Z"), ZoneOffset.UTC));

    // when
    Clock seoul = clock.withZone(ZoneId.of("Asia/Seoul"));

    // then
    assertThat(seoul.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    assertThat(seoul.instant()).isEqualTo(Instant.parse("2026-11-28T10:04:59.999999Z"));
  }

  @Test
  @DisplayName("운영 Clock 빈은 시스템 UTC Clock을 마이크로초 내림으로 감싼다")
  void clockBean_wrapsSystemUtcWithMicrosecondClock() {
    // when
    Clock clock = new ClockConfig().clock();

    // then: 래핑이 빠지면 Linux JDK에서 나노초가 그대로 판정·저장에 쓰인다
    assertThat(clock).isEqualTo(new MicrosecondClock(Clock.systemUTC()));
  }

  @Test
  @DisplayName("운영 Clock 빈의 시간대는 UTC이고 millis()가 예외 없이 동작한다")
  void clockBean_utcAndMillisWork() {
    // when
    Clock clock = new ClockConfig().clock();

    // then
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(clock.millis()).isPositive();
  }
}
