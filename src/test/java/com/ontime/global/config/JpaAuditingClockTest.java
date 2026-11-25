package com.ontime.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ontime.TestcontainersConfiguration;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 생성/수정 시각이 시스템 시각이 아니라 주입받은 Clock 으로 채워지는지 실제 DB 저장으로 확인한다. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, JpaAuditingClockTest.FixedClockConfig.class})
class JpaAuditingClockTest {

  // 실제 현재 시각과 확실히 다른 과거 시각. 시스템 시각으로 채워지면 같을 수 없다.
  private static final Instant FIXED_NOW = Instant.parse("2020-01-01T00:00:00Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
  }

  @Autowired private UserRepository userRepository;

  @AfterEach
  void tearDown() {
    userRepository.deleteAllInBatch();
  }

  @Test
  @DisplayName("엔티티를 저장하면 createdAt과 updatedAt이 주입받은 Clock의 시각으로 DB에 저장된다")
  void save_withFixedClock_fillsAuditTimesFromClock() {
    // given
    Long userId = userRepository.save(User.create("지각대장")).getId();

    // when: DB에서 다시 읽는다
    User saved = userRepository.findById(userId).orElseThrow();

    // then
    assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
  }
}
