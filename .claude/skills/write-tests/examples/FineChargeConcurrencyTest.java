package com.ontime.fine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ontime.fine.domain.LedgerType;
import com.ontime.fine.repository.FineLedgerRepository;
import com.ontime.fine.service.FineService;
import com.ontime.support.IntegrationTestSupport;
import com.ontime.support.TestDataFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 예시: 동시성 테스트.
 *
 * <p>같은 참석(attendance)에 대한 지각 벌금 부과가 동시에 여러 번 들어와도 원장에는 CHARGE 한 행만 남아야 한다. 애플리케이션 검사가
 * 아니라 DB 부분 유니크 인덱스가 막는지를 확인한다.
 *
 * <p>- 클래스에 @Transactional 을 붙이지 않는다. 스레드마다 별도 트랜잭션이어야 경합이 재현된다.
 *
 * <p>- 가상 스레드 대신 고정 스레드 풀을 쓴다 (Java 17).
 */
class FineChargeConcurrencyTest extends IntegrationTestSupport {

  private static final int THREADS = 10;

  @Autowired private FineService fineService;
  @Autowired private FineLedgerRepository fineLedgerRepository;
  @Autowired private TestDataFactory data;

  @AfterEach
  void tearDown() {
    data.deleteAll();
  }

  @Test
  @DisplayName("같은 참석에 지각 벌금을 동시에 10번 부과해도 CHARGE는 1건만 기록된다")
  void chargeLateFine_concurrentRequests_onlyOneChargeRecorded() throws InterruptedException {
    // given
    Long attendanceId = data.lateAttendanceWithFine(3_000L);
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(THREADS);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger duplicate = new AtomicInteger();

    // when
    for (int i = 0; i < THREADS; i++) {
      executor.submit(
          () -> {
            ready.countDown();
            try {
              start.await();
              fineService.chargeLateFine(attendanceId);
              success.incrementAndGet();
            } catch (DataIntegrityViolationException e) {
              duplicate.incrementAndGet();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    ready.await();
    start.countDown(); // 모든 스레드가 준비된 뒤 동시에 출발
    done.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    // then
    assertThat(fineLedgerRepository.countByAttendanceIdAndType(attendanceId, LedgerType.CHARGE))
        .isEqualTo(1);
    assertThat(success.get()).isEqualTo(1);
    assertThat(duplicate.get()).isEqualTo(THREADS - 1);
  }
}
