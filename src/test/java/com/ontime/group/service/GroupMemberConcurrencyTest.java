package com.ontime.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.TestcontainersConfiguration;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.InviteCode;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.repository.GroupMemberRepository;
import com.ontime.group.repository.InviteCodeRepository;
import com.ontime.group.repository.MeetingGroupRepository;
import com.ontime.support.MutableClock;
import com.ontime.support.MutableClockConfig;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 동시 요청과 DB 제약 테스트. 앱의 사전 검사를 여러 요청이 동시에 통과해도 DB 유니크 제약이 중복 행을 막고, 그 위반이 정해진
 * ErrorCode 로 끝나는지 본다.
 *
 * <p>스레드마다 별도 트랜잭션이어야 경합이 재현되므로 클래스에 {@code @Transactional} 을 붙이지 않고 {@code @AfterEach} 에서
 * 정리한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfig.class})
class GroupMemberConcurrencyTest {

  private static final int THREADS = 10;

  @Autowired private GroupMemberService groupMemberService;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private MutableClock clock;

  private User owner;
  private MeetingGroup group;

  @BeforeEach
  void setUp() {
    clock.setInstant(MutableClockConfig.NOW);
    owner = userRepository.save(User.create("방장"));
    group = saveGroupOwnedBy(owner);
  }

  @AfterEach
  void tearDown() {
    inviteCodeRepository.deleteAllInBatch();
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  private MeetingGroup saveGroupOwnedBy(User groupOwner) {
    MeetingGroup saved =
        meetingGroupRepository.save(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", 37.4979, 127.0276),
                LocalDate.of(2026, 12, 31),
                LocalTime.of(19, 0),
                ArrivalRule.of(5, 60, 200),
                FineRule.money(3000),
                MutableClockConfig.NOW));
    groupMemberRepository.save(GroupMember.owner(saved, groupOwner));
    return saved;
  }

  /** 같은 작업을 THREADS 개 스레드에서 동시에 시작하고, 결과(성공 값 또는 예외)를 모은다. */
  private <T> List<Object> runConcurrently(Callable<T> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Object> results = new CopyOnWriteArrayList<>();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                try {
                  results.add(task.call());
                } catch (Exception e) {
                  results.add(e);
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
    return results;
  }

  /** 결과를 종류별로 센다. 성공은 "OK", BusinessException 은 ErrorCode, 그 밖의 예외는 클래스. */
  private static Map<Object, Integer> countByKind(List<Object> results) {
    Map<Object, Integer> counts = new ConcurrentHashMap<>();
    for (Object result : results) {
      Object kind;
      if (result instanceof BusinessException e) {
        kind = e.getErrorCode();
      } else if (result instanceof Exception e) {
        kind = e.getClass();
      } else {
        kind = "OK";
      }
      counts.merge(kind, 1, Integer::sum);
    }
    return counts;
  }

  @Test
  @DisplayName("같은 사용자가 동시에 10번 참여하면 1번만 성공하고 나머지는 ALREADY_MEMBER, 멤버 행은 1개다")
  void join_sameUserConcurrently_onlyOneMembership() throws Exception {
    // given
    Long guestId = userRepository.save(User.create("손님")).getId();
    String code = groupMemberService.issueInviteCode(owner.getId(), group.getId()).code();

    // when
    List<Object> results = runConcurrently(() -> groupMemberService.join(guestId, code));

    // then
    assertThat(countByKind(results))
        .containsOnlyKeys("OK", ErrorCode.ALREADY_MEMBER)
        .containsEntry("OK", 1)
        .containsEntry(ErrorCode.ALREADY_MEMBER, THREADS - 1);
    assertThat(groupMemberRepository.findByGroupIdAndUserId(group.getId(), guestId)).isPresent();
    assertThat(groupMemberRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("같은 그룹에 동시에 처음 발급해도 코드 행은 1개이고, 실패는 409로만 끝나며 DB 코드는 성공 응답 중 하나다")
  void issueInviteCode_concurrentFirstIssue_singleRowAndOnlyConflicts() throws Exception {
    // when
    List<Object> results =
        runConcurrently(
            () -> groupMemberService.issueInviteCode(owner.getId(), group.getId()).code());

    // then: 성공, 첫 발급 경합(CONCURRENT_UPDATE), 재발급 경합(낙관적 락) 외의 결과는 없어야 한다
    Map<Object, Integer> counts = countByKind(results);
    assertThat(counts.keySet())
        .isSubsetOf("OK", ErrorCode.CONCURRENT_UPDATE, ObjectOptimisticLockingFailureException.class);
    assertThat(counts).containsKey("OK");
    assertThat(inviteCodeRepository.count()).isEqualTo(1);
    String storedCode = inviteCodeRepository.findAll().get(0).getCode();
    assertThat(results).contains(storedCode);
  }

  @Test
  @DisplayName("다른 그룹이 이미 쓰는 코드를 저장하면 DB 코드 유니크 제약이 막는다")
  void save_duplicateCodeAcrossGroups_rejectedByConstraint() {
    // given
    inviteCodeRepository.save(InviteCode.issue(group, "SAME2345", MutableClockConfig.NOW));
    MeetingGroup otherGroup = saveGroupOwnedBy(userRepository.save(User.create("남")));

    // when & then
    assertThatThrownBy(
            () ->
                inviteCodeRepository.save(
                    InviteCode.issue(otherGroup, "SAME2345", MutableClockConfig.NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(inviteCodeRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("한 그룹에 코드 행을 두 개 저장하면 DB 그룹당 1개 제약이 막는다")
  void save_secondCodeForSameGroup_rejectedByConstraint() {
    // given
    inviteCodeRepository.save(InviteCode.issue(group, "FRST2345", MutableClockConfig.NOW));

    // when & then
    assertThatThrownBy(
            () ->
                inviteCodeRepository.save(
                    InviteCode.issue(group, "SCND2345", MutableClockConfig.NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(inviteCodeRepository.count()).isEqualTo(1);
  }
}
