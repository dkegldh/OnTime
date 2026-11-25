package com.ontime.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 서버 시각을 {@link #NOW} 로 시작하는, 옮길 수 있는 Clock 을 {@code @Primary} 로 등록한다. 같은 설정을 쓰는 통합 테스트는 Spring
 * 컨텍스트를 공유한다. 테스트마다 {@code @BeforeEach} 에서 {@code clock.setInstant(NOW)} 로 되돌린다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

  /** 2026-11-25 12:00 KST */
  public static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  @Bean
  @Primary
  public MutableClock mutableClock() {
    return new MutableClock(NOW);
  }
}
