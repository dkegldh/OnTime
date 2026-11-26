package com.ontime.meeting.dto;

import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.domain.MeetingStatus;
import java.time.Instant;

/**
 * 그룹의 회차 목록 한 항목.
 *
 * @param myStatus 요청자의 참석 상태. 회차가 시작한 뒤 참여해 참석 행이 없으면 null
 */
public record MeetingSummary(
    Long id,
    Instant meetingAt,
    MeetingStatus status,
    String placeName,
    AttendanceStatus myStatus) {}
