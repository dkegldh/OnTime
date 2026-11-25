package com.ontime.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예외가 MVC 요청 처리 중에 던져졌을 때 GlobalExceptionHandler 가 실제로 잡아 응답을 만드는지 본다. */
class GlobalExceptionHandlerTest {

  @RestController
  static class ThrowingController {
    @GetMapping("/test/optimistic-lock")
    void optimisticLock() {
      throw new ObjectOptimisticLockingFailureException("MeetingGroup", 1L);
    }
  }

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ThrowingController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  @DisplayName("요청 처리 중 낙관적 락 충돌이 나면 409 CONCURRENT_UPDATE 응답이 된다")
  void optimisticLockFailure_duringRequest_respondsConcurrentUpdate() throws Exception {
    // when
    ResultActions result = mockMvc.perform(get("/test/optimistic-lock"));

    // then
    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
        .andExpect(jsonPath("$.message").value("다른 요청과 동시에 바뀌었어요. 다시 시도해 주세요."));
  }
}
