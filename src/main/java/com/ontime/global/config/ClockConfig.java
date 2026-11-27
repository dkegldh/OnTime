package com.ontime.global.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  /** DB {@code TIMESTAMPTZ} 의 정밀도. 서버 시각을 이 단위로 자른다. */
  public static final Duration TICK = Duration.ofNanos(1_000);

  @Bean
  public Clock clock() {
    return microsecondClock(Clock.systemUTC());
  }

  /**
   * 마이크로초 단위로 내림하는 Clock.
   *
   * <p>PostgreSQL {@code TIMESTAMPTZ} 는 마이크로초까지 저장하고 그보다 작은 값은 반올림한다. 서버 시계가 나노초를 주면(Linux
   * JDK) 유예 끝 0.5µs 안쪽 도착이 {@code ARRIVED} 로 판정되는데 {@code arrived_at} 은 유예 끝 정각으로 저장된다. 판정에 쓰는
   * 시각과 저장되는 시각이 항상 같도록 미리 자른다.
   */
  public static Clock microsecondClock(Clock base) {
    return Clock.tick(base, TICK);
  }
}
