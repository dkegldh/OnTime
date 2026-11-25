package com.ontime.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 같은 사용자가 같은 코드로 동시에 참여할 때, 앱의 "이미 멤버인가" 검사를 동시에 통과하더라도 (group_id, user_id) 유니크 제약이
 * 중복 행을 막고 나머지 요청은 ALREADY_MEMBER 로 끝나는지 본다.
 *
 * <p>스레드마다 별도 트랜잭션이어야 경합이 재현되므로 클래스에 {@code @Transactional} 을 붙이지 않고 {@code @AfterEach} 에서
 * 정리한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupJoinConcurrencyTest {

  private static final int THREADS = 10;

  @Autowired private GroupMemberService groupMemberService;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private Clock clock;

  @AfterEach
  void tearDown() {
    inviteCodeRepository.deleteAllInBatch();
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  @Test
  @DisplayName("같은 사용자가 동시에 10번 참여하면 1번만 성공하고 나머지는 ALREADY_MEMBER, 멤버 행은 1개다")
  void join_sameUserConcurrently_onlyOneMembership() throws Exception {
    // given
    User owner = userRepository.save(User.create("방장"));
    Long guestId = userRepository.save(User.create("손님")).getId();
    MeetingGroup group =
        meetingGroupRepository.save(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", 37.4979, 127.0276),
                LocalDate.now(clock.withZone(MeetingGroup.MEETING_ZONE)).plusDays(30),
                LocalTime.of(19, 0),
                ArrivalRule.of(5, 60, 200),
                FineRule.money(3000),
                Instant.now(clock)));
    groupMemberRepository.save(GroupMember.owner(group, owner));
    String code = groupMemberService.issueInviteCode(owner.getId(), group.getId()).code();

    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    ConcurrentHashMap<Object, AtomicInteger> failures = new ConcurrentHashMap<>();
    List<Future<?>> futures = new ArrayList<>();

    // when
    for (int i = 0; i < THREADS; i++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                try {
                  groupMemberService.join(guestId, code);
                  success.incrementAndGet();
                } catch (BusinessException e) {
                  failures.computeIfAbsent(e.getErrorCode(), k -> new AtomicInteger()).incrementAndGet();
                } catch (RuntimeException e) {
                  failures.computeIfAbsent(e.getClass(), k -> new AtomicInteger()).incrementAndGet();
                }
                return null;
              }));
    }
    ready.await();
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    // then
    assertThat(success.get()).isEqualTo(1);
    assertThat(failures).containsOnlyKeys(ErrorCode.ALREADY_MEMBER);
    assertThat(failures.get(ErrorCode.ALREADY_MEMBER).get()).isEqualTo(THREADS - 1);
    assertThat(groupMemberRepository.findByGroupIdAndUserId(group.getId(), guestId)).isPresent();
    assertThat(groupMemberRepository.count()).isEqualTo(2);
  }
}
