package com.ontime.group.dto;

import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.FineType;
import com.ontime.group.domain.GroupType;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.group.domain.Weekday;
import com.ontime.group.domain.Weekdays;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 그룹 생성 요청. 날짜와 시각은 KST 기준이다.
 *
 * <p>{@code type} 을 생략하면 일회성(ONE_TIME)이다. 일회성은 {@code startDate} 가 약속 날짜이고 {@code endDate}, {@code
 * daysOfWeek} 를 무시한다. 정기 모임(RECURRING)은 {@code startDate}~{@code endDate}(양 끝 포함, 최대 90일) 안의 {@code
 * daysOfWeek} 마다 회차가 열린다.
 */
public record CreateMeetingGroupRequest(
    GroupType type,
    @NotBlank @Size(max = MeetingGroup.NAME_MAX_LENGTH) String name,
    @NotBlank @Size(max = Place.NAME_MAX_LENGTH) String placeName,
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
    @NotNull LocalDate startDate,
    LocalDate endDate,
    List<Weekday> daysOfWeek,
    @NotNull LocalTime meetingTime,
    @NotNull @Min(0) @Max(60) Integer graceMinutes,
    @NotNull @Min(1) @Max(180) Integer absentAfterMinutes,
    @NotNull @Min(50) @Max(1000) Integer arrivalRadiusM,
    @NotNull FineType fineType,
    Long fineAmount,
    String penaltyText) {

  public static final long MIN_FINE_AMOUNT = 1L;
  public static final long MAX_FINE_AMOUNT = 1_000_000L;

  /** 생략하면 일회성이다. (정기 모임 추가 전 요청과 호환) */
  public GroupType groupType() {
    return type == null ? GroupType.ONE_TIME : type;
  }

  @Schema(hidden = true)
  @AssertTrue(message = "종료일은 시작일부터 90일 안으로 정해 주세요.")
  public boolean isRecurringPeriodValid() {
    if (groupType() != GroupType.RECURRING || startDate == null) {
      return true;
    }
    return endDate != null
        && !endDate.isBefore(startDate)
        && ChronoUnit.DAYS.between(startDate, endDate) + 1 <= MeetingGroup.RECURRING_MAX_DAYS;
  }

  @Schema(hidden = true)
  @AssertTrue(message = "요일을 하나 이상 골라 주세요.")
  public boolean isRecurringDaysPresent() {
    if (groupType() != GroupType.RECURRING) {
      return true;
    }
    return daysOfWeek != null && !daysOfWeek.isEmpty() && !daysOfWeek.contains(null);
  }

  @Schema(hidden = true)
  @AssertTrue(message = "결석 판정 시간은 유예시간보다 길어야 해요.")
  public boolean isAbsentAfterLongerThanGrace() {
    if (graceMinutes == null || absentAfterMinutes == null) {
      return true;
    }
    return absentAfterMinutes > graceMinutes;
  }

  @Schema(hidden = true)
  @AssertTrue(message = "벌금은 1원~1,000,000원, 벌칙은 100자 이내로 입력해 주세요.")
  public boolean isFineRuleComplete() {
    if (fineType == null) {
      return true;
    }
    // 쓰지 않는 쪽 값(TEXT의 금액, MONEY의 벌칙 문구)은 버리므로 검사하지 않는다.
    return switch (fineType) {
      case MONEY ->
          fineAmount != null && fineAmount >= MIN_FINE_AMOUNT && fineAmount <= MAX_FINE_AMOUNT;
      case TEXT ->
          penaltyText != null
              && !penaltyText.isBlank()
              && penaltyText.length() <= FineRule.PENALTY_TEXT_MAX_LENGTH;
    };
  }

  /** 정기 모임 요일. 중복은 하나로 합친다. */
  public Weekdays toWeekdays() {
    return Weekdays.of(daysOfWeek);
  }

  public Place toPlace() {
    return Place.of(placeName, latitude, longitude);
  }

  public ArrivalRule toArrivalRule() {
    return ArrivalRule.of(graceMinutes, absentAfterMinutes, arrivalRadiusM);
  }

  /** MONEY면 벌칙 문구를, TEXT면 금액을 버린다. */
  public FineRule toFineRule() {
    return switch (fineType) {
      case MONEY -> FineRule.money(fineAmount);
      case TEXT -> FineRule.text(penaltyText);
    };
  }
}
