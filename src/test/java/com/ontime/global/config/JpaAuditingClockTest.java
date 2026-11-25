package com.ontime.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ontime.TestcontainersConfiguration;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.support.MutableClock;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 생성/수정 시각이 시스템 시각이 아니라 주입받은 Clock 으로, 그리고 저장하는 그 순간의 Clock 값으로 채워지는지 실제 DB 저장으로
 * 확인한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, JpaAuditingClockTest.MutableClockConfig.class})
class JpaAuditingClockTest {

  // 실제 현재 시각과 확실히 다른 과거 시각. 시스템 시각으로 채워지면 같을 수 없다.
  private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant T2 = Instant.parse("2020-01-01T01:30:00Z");

  @TestConfiguration(proxyBeanMethods = false)
  static class MutableClockConfig {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(T1);
    }
  }

  @Autowired private MutableClock clock;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;

  @BeforeEach
  void setUp() {
    clock.setInstant(T1);
  }

  @AfterEach
  void tearDown() {
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  private MeetingGroup newGroup() {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        LocalDate.of(2020, 1, 10),
        LocalTime.of(19, 0),
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        Instant.now(clock));
  }

  @Test
  @DisplayName("엔티티를 저장하면 createdAt과 updatedAt이 주입받은 Clock의 시각으로 DB에 저장된다")
  void save_withFixedClock_fillsAuditTimesFromClock() {
    // given
    Long userId = userRepository.save(User.create("지각대장")).getId();

    // when: DB에서 다시 읽는다
    User saved = userRepository.findById(userId).orElseThrow();

    // then
    assertThat(saved.getCreatedAt()).isEqualTo(T1);
    assertThat(saved.getUpdatedAt()).isEqualTo(T1);
  }

  @Test
  @DisplayName("Clock이 바뀐 뒤 저장한 엔티티는 바뀐 시각으로 저장된다 (시각을 한 번만 구해 두지 않는다)")
  void save_afterClockAdvances_usesCurrentClockTime() {
    // given
    userRepository.save(User.create("먼저"));
    clock.setInstant(T2);

    // when
    Long laterId = userRepository.save(User.create("나중")).getId();

    // then
    User later = userRepository.findById(laterId).orElseThrow();
    assertThat(later.getCreatedAt()).isEqualTo(T2);
    assertThat(later.getUpdatedAt()).isEqualTo(T2);
  }

  @Test
  @DisplayName("Clock이 바뀐 뒤 수정하면 updatedAt만 바뀐 시각이 되고 createdAt은 그대로다")
  void update_afterClockAdvances_changesOnlyUpdatedAt() {
    // given: T1에 저장
    MeetingGroup group = meetingGroupRepository.save(newGroup());
    clock.setInstant(T2);

    // when: T2에 이름 변경
    group.rename("토요일 모임");
    meetingGroupRepository.saveAndFlush(group);

    // then
    MeetingGroup updated = meetingGroupRepository.findById(group.getId()).orElseThrow();
    assertThat(updated.getName()).isEqualTo("토요일 모임");
    assertThat(updated.getCreatedAt()).isEqualTo(T1);
    assertThat(updated.getUpdatedAt()).isEqualTo(T2);
  }
}
