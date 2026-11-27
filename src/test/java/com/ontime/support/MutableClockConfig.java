package com.ontime.support;

import com.ontime.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 서버 시각을 {@link #NOW} 로 시작하는, 옮길 수 있는 Clock 을 등록한다. 같은 설정을 쓰는 통합 테스트는 Spring 컨텍스트를 공유한다.
 * 테스트마다 {@code @BeforeEach} 에서 {@code clock.setInstant(NOW)} 로 되돌린다.
 *
 * <p>서비스가 주입받는 {@code @Primary} Clock 은 운영과 같은 {@link ClockConfig#microsecondClock} 으로 감싼다. 테스트가
 * {@link MutableClock} 에 나노초 시각을 넣어도 운영처럼 마이크로초로 잘린 시각으로 판정하고 저장한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

  /** 2026-11-25 12:00 KST */
  public static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  @Bean
  public MutableClock mutableClock() {
    return new MutableClock(NOW);
  }

  @Bean
  @Primary
  public Clock serverClock(MutableClock mutableClock) {
    return ClockConfig.microsecondClock(mutableClock);
  }
}
