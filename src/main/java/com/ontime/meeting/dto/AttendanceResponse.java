package com.ontime.meeting.dto;

import com.ontime.meeting.domain.AttendanceStatus;
import java.time.Instant;

/** 회차 상세의 멤버별 참석 상태. */
public record AttendanceResponse(
    Long userId,
    String nickname,
    String profileImageUrl,
    AttendanceStatus status,
    Instant departedAt,
    Instant arrivedAt) {}
