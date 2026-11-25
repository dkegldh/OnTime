package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArrivalRuleTest {

  @Test
  @DisplayName("결석 판정 시간이 유예시간보다 1분 길면 만들어진다")
  void of_absentAfterOneMinuteLongerThanGrace_created() {
    // when
    ArrivalRule rule = ArrivalRule.of(10, 11, 200);

    // then
    assertThat(rule.getGraceMinutes()).isEqualTo(10);
    assertThat(rule.getAbsentAfterMinutes()).isEqualTo(11);
    assertThat(rule.getArrivalRadiusM()).isEqualTo(200);
  }

  @Test
  @DisplayName("결석 판정 시간이 유예시간과 같으면 VALIDATION_FAILED로 거절된다")
  void of_absentAfterEqualsGrace_rejected() {
    assertThatThrownBy(() -> ArrivalRule.of(10, 10, 200))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("결석 판정 시간이 유예시간보다 짧으면 VALIDATION_FAILED로 거절된다")
  void of_absentAfterShorterThanGrace_rejected() {
    assertThatThrownBy(() -> ArrivalRule.of(10, 9, 200))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }
}
