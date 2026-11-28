package com.ontime.group.dto;

import com.ontime.group.domain.FineType;
import com.ontime.group.domain.GroupMember;
import com.ontime.group.domain.GroupRole;
import com.ontime.group.domain.GroupType;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Weekday;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 그룹 상세. 생성, 수정 응답도 같은 형식이다. {@code endDate}, {@code daysOfWeek} 는 일회성이면 null이다. */
public record MeetingGroupResponse(
    Long id,
    String name,
    GroupType type,
    String placeName,
    double latitude,
    double longitude,
    LocalDate startDate,
    LocalDate endDate,
    List<Weekday> daysOfWeek,
    LocalTime meetingTime,
    int graceMinutes,
    int absentAfterMinutes,
    int arrivalRadiusM,
    FineType fineType,
    long fineAmount,
    String penaltyText,
    GroupRole myRole,
    Instant createdAt) {

  public static MeetingGroupResponse of(GroupMember membership) {
    MeetingGroup group = membership.getGroup();
    return new MeetingGroupResponse(
        group.getId(),
        group.getName(),
        group.getType(),
        group.getPlace().getName(),
        group.getPlace().getLatitude(),
        group.getPlace().getLongitude(),
        group.getStartDate(),
        group.getEndDate(),
        group.getDaysOfWeek() == null ? null : group.getDaysOfWeek().toList(),
        group.getMeetingTime(),
        group.getArrivalRule().getGraceMinutes(),
        group.getArrivalRule().getAbsentAfterMinutes(),
        group.getArrivalRule().getArrivalRadiusM(),
        group.getFineRule().getFineType(),
        group.getFineRule().getFineAmount(),
        group.getFineRule().getPenaltyText(),
        membership.getRole(),
        group.getCreatedAt());
  }
}
