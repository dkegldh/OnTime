package com.ontime.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
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
  @DisplayName("운영 Clock 빈은 마이크로초로 자른 UTC 시각을 준다")
  void clockBean_microsecondPrecisionUtc() {
    // given
    Clock clock = new ClockConfig().clock();

    // when
    Instant now = Instant.now(clock);

    // then: tick 래핑이 빠지면 Linux JDK에서 나노초가 남는다. (macOS는 원래 마이크로초라 아래 줄로 래핑 자체를 확인)
    assertThat(now.getNano() % 1_000).isZero();
    assertThat(clock).isEqualTo(Clock.tick(Clock.systemUTC(), ClockConfig.TICK));
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
