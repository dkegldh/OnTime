package com.ontime.global.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA Auditing 의 생성/수정 시각도 주입받은 {@link Clock} 으로 채운다. (테스트에서 Clock.fixed 로 고정 가능) */
@EnableJpaAuditing(dateTimeProviderRef = JpaConfig.DATE_TIME_PROVIDER)
@Configuration
public class JpaConfig {

  static final String DATE_TIME_PROVIDER = "auditingDateTimeProvider";

  @Bean(name = DATE_TIME_PROVIDER)
  public DateTimeProvider auditingDateTimeProvider(Clock clock) {
    return () -> Optional.of(Instant.now(clock));
  }
}
