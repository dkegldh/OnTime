package com.ontime.meeting.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** 도착 요청. 현재 위치만 받고, 도착 시각은 서버 시각을 쓴다. */
public record ArriveRequest(
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {}
