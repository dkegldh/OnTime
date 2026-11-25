package com.ontime.group.dto;

import com.ontime.group.domain.GroupRole;
import com.ontime.group.domain.GroupType;
import java.time.LocalDate;
import java.time.LocalTime;

/** 그룹 목록의 한 항목. */
public record MeetingGroupSummary(
    Long id,
    String name,
    GroupType type,
    String placeName,
    LocalDate startDate,
    LocalTime meetingTime,
    GroupRole myRole) {}
