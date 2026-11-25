package com.ontime.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("낙관적 락 충돌은 409 CONCURRENT_UPDATE 응답이 된다")
  void handleOptimisticLock_conflict_respondsConcurrentUpdate() {
    // given
    ObjectOptimisticLockingFailureException e =
        new ObjectOptimisticLockingFailureException("MeetingGroup", 1L);

    // when
    ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(e);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody().code()).isEqualTo("CONCURRENT_UPDATE");
    assertThat(response.getBody().message())
        .isEqualTo("다른 요청과 동시에 바뀌었어요. 다시 시도해 주세요.");
  }
}
