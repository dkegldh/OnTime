package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FineRuleTest {

  @Test
  @DisplayName("벌금 1원은 MONEY 규칙으로 만들어지고 벌칙 문구는 없다")
  void money_oneWon_createdWithoutPenaltyText() {
    // when
    FineRule rule = FineRule.money(1);

    // then
    assertThat(rule.getFineType()).isEqualTo(FineType.MONEY);
    assertThat(rule.getFineAmount()).isEqualTo(1L);
    assertThat(rule.getPenaltyText()).isNull();
  }

  @Test
  @DisplayName("벌금 0원은 VALIDATION_FAILED로 거절된다")
  void money_zero_rejected() {
    assertThatThrownBy(() -> FineRule.money(0))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("벌칙(TEXT) 규칙은 금액이 0원이다")
  void text_validPenalty_amountIsZero() {
    // when
    FineRule rule = FineRule.text("커피 사기");

    // then
    assertThat(rule.getFineType()).isEqualTo(FineType.TEXT);
    assertThat(rule.getFineAmount()).isZero();
    assertThat(rule.getPenaltyText()).isEqualTo("커피 사기");
  }

  @Test
  @DisplayName("벌칙 문구가 공백뿐이면 VALIDATION_FAILED로 거절된다")
  void text_blank_rejected() {
    assertThatThrownBy(() -> FineRule.text("  "))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("벌칙 문구가 null이면 VALIDATION_FAILED로 거절된다")
  void text_null_rejected() {
    assertThatThrownBy(() -> FineRule.text(null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }
}
