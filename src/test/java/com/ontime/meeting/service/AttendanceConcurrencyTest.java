package com.ontime.meeting.service;

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
import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.dto.ArriveRequest;
import com.ontime.meeting.repository.AttendanceRepository;
import com.ontime.meeting.repository.MeetingRepository;
import com.ontime.support.MutableClock;
import com.ontime.support.MutableClockConfig;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.time.Instant;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 같은 참석 행에 출발/도착 요청이 동시에 와도 한 번만 반영되는지 본다. 앱의 상태 검사를 여러 요청이 동시에 통과해도 {@code
 * @Version} 낙관적 락이 나중 커밋을 막아야 한다.
 *
 * <p>스레드마다 별도 트랜잭션이어야 경합이 재현되므로 클래스에 {@code @Transactional} 을 붙이지 않고 {@code @AfterEach} 에서
 * 정리한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfig.class})
class AttendanceConcurrencyTest {

  private static final int THREADS = 10;

  // 약속 2026-12-31 19:00 KST = 10:00Z. 1시간 전이라 버튼이 열려 있고 유예 전이다. (출발/도착 모두 이 시각에 누른다)
  private static final Instant BUTTON_NOW = Instant.parse("2026-12-31T09:00:00Z");

  @Autowired private MeetingService meetingService;
  @Autowired private UserRepository userRepository;
  @Autowired private MeetingGroupRepository meetingGroupRepository;
  @Autowired private GroupMemberRepository groupMemberRepository;
  @Autowired private InviteCodeRepository inviteCodeRepository;
  @Autowired private MeetingRepository meetingRepository;
  @Autowired private AttendanceRepository attendanceRepository;
  @Autowired private MutableClock clock;

  private User owner;
  private Meeting meeting;

  @BeforeEach
  void setUp() {
    clock.setInstant(MutableClockConfig.NOW);
    owner = userRepository.save(User.create("방장"));
    MeetingGroup group =
        meetingGroupRepository.save(
            MeetingGroup.createOneTime(
                "금요일 모임",
                Place.of("강남역", 37.4979, 127.0276),
                LocalDate.of(2026, 12, 31),
                LocalTime.of(19, 0),
                ArrivalRule.of(5, 60, 200),
                FineRule.money(3000),
                MutableClockConfig.NOW));
    groupMemberRepository.save(GroupMember.owner(group, owner));
    meeting = meetingRepository.save(Meeting.oneTimeOf(group));
    attendanceRepository.save(Attendance.ready(meeting, owner));
    clock.setInstant(BUTTON_NOW);
  }

  @AfterEach
  void tearDown() {
    attendanceRepository.deleteAllInBatch();
    meetingRepository.deleteAllInBatch();
    inviteCodeRepository.deleteAllInBatch();
    groupMemberRepository.deleteAllInBatch();
    meetingGroupRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
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
  @DisplayName("같은 사용자가 동시에 10번 출발하면 1번만 성공하고, 나머지는 INVALID_STATE 또는 낙관적 락 충돌, 버전은 1만 오른다")
  void depart_sameUserConcurrently_appliedOnce() throws Exception {
    // when
    List<Object> results =
        runConcurrently(() -> meetingService.depart(owner.getId(), meeting.getId()));

    // then: 먼저 커밋된 뒤 읽은 요청은 MOVING을 보고 INVALID_STATE, 같이 READY를 읽은 요청은 @Version 충돌
    // (ObjectOptimisticLockingFailureException 은 응답에서 409 CONCURRENT_UPDATE 가 된다)
    Map<Object, Integer> counts = countByKind(results);
    assertThat(counts.keySet())
        .isSubsetOf("OK", ErrorCode.INVALID_STATE, ObjectOptimisticLockingFailureException.class);
    assertThat(counts).containsEntry("OK", 1);
    Attendance saved = attendanceRepository.findAll().get(0);
    assertThat(saved.getStatus()).isEqualTo(AttendanceStatus.MOVING);
    assertThat(saved.getDepartedAt()).isEqualTo(BUTTON_NOW);
    assertThat(saved.getVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("같은 사용자가 동시에 10번 도착하면 1번만 성공하고, 나머지는 INVALID_STATE 또는 낙관적 락 충돌, 버전은 1만 오른다")
  void arrive_sameUserConcurrently_appliedOnce() throws Exception {
    // given: 약속 장소에서 유예 전
    ArriveRequest request = new ArriveRequest(37.4979, 127.0276);

    // when
    List<Object> results =
        runConcurrently(() -> meetingService.arrive(owner.getId(), meeting.getId(), request));

    // then: 먼저 커밋된 뒤 읽은 요청은 ARRIVED를 보고 INVALID_STATE, 같이 READY를 읽은 요청은 @Version 충돌
    Map<Object, Integer> counts = countByKind(results);
    assertThat(counts.keySet())
        .isSubsetOf("OK", ErrorCode.INVALID_STATE, ObjectOptimisticLockingFailureException.class);
    assertThat(counts).containsEntry("OK", 1);
    Attendance saved = attendanceRepository.findAll().get(0);
    assertThat(saved.getStatus()).isEqualTo(AttendanceStatus.ARRIVED);
    assertThat(saved.getArrivedAt()).isEqualTo(BUTTON_NOW);
    assertThat(saved.getVersion()).isEqualTo(1L);
  }
}
