package com.ontime.group.domain;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 지각/결석 판정과 도착 인정 반경. 회차를 만들 때 그대로 복사(스냅샷)한다. */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArrivalRule {

  @Column(nullable = false)
  private int graceMinutes;

  @Column(nullable = false)
  private int absentAfterMinutes;

  @Column(name = "arrival_radius_m", nullable = false)
  private int arrivalRadiusM;

  private ArrivalRule(int graceMinutes, int absentAfterMinutes, int arrivalRadiusM) {
    this.graceMinutes = graceMinutes;
    this.absentAfterMinutes = absentAfterMinutes;
    this.arrivalRadiusM = arrivalRadiusM;
  }

  /** 결석 판정 시간은 유예시간보다 길어야 한다. (같으면 지각 상태를 거치지 못한다) */
  public static ArrivalRule of(int graceMinutes, int absentAfterMinutes, int arrivalRadiusM) {
    if (absentAfterMinutes <= graceMinutes) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new ArrivalRule(graceMinutes, absentAfterMinutes, arrivalRadiusM);
  }
}
