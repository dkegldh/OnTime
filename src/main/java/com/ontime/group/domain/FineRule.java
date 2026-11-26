package com.ontime.group.domain;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지각 벌금 규칙. 회차를 만들 때 그대로 복사(스냅샷)한다.
 *
 * <p>MONEY는 금액만, TEXT는 벌칙 문구만 가진다. TEXT의 금액은 0이다. (원장에 amount = 0으로 기록해 횟수를 센다)
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FineRule {

  public static final int PENALTY_TEXT_MAX_LENGTH = 100;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private FineType fineType;

  @Column(nullable = false)
  private long fineAmount;

  @Column(length = PENALTY_TEXT_MAX_LENGTH)
  private String penaltyText;

  private FineRule(FineType fineType, long fineAmount, String penaltyText) {
    this.fineType = fineType;
    this.fineAmount = fineAmount;
    this.penaltyText = penaltyText;
  }

  public static FineRule money(long amount) {
    if (amount <= 0) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new FineRule(FineType.MONEY, amount, null);
  }

  public static FineRule text(String penaltyText) {
    if (penaltyText == null || penaltyText.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new FineRule(FineType.TEXT, 0L, penaltyText);
  }

  /** 회차 스냅샷용 복사본. */
  public FineRule copy() {
    return new FineRule(fineType, fineAmount, penaltyText);
  }
}
