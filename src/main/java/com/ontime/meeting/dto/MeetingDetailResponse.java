package com.ontime.meeting.dto;

import com.ontime.group.domain.FineType;
import com.ontime.meeting.domain.AttendanceStatus;
import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.domain.MeetingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 회차 상세. 장소와 규칙은 그룹이 아니라 회차에 복사해 둔 값(스냅샷)이다.
 *
 * @param myStatus 요청자의 참석 상태. 회차가 시작한 뒤 참여해 참석 행이 없으면 null
 * @param attendances 멤버별 참석 상태 (회차에 등록된 순)
 */
public record MeetingDetailResponse(
    Long id,
    Long groupId,
    String groupName,
    MeetingStatus status,
    Instant meetingAt,
    String placeName,
    double latitude,
    double longitude,
    int graceMinutes,
    int absentAfterMinutes,
    int arrivalRadiusM,
    FineType fineType,
    long fineAmount,
    String penaltyText,
    AttendanceStatus myStatus,
    List<AttendanceResponse> attendances) {

  public static MeetingDetailResponse of(
      Meeting meeting, Long userId, List<AttendanceResponse> attendances) {
    AttendanceStatus myStatus =
        attendances.stream()
            .filter(a -> Objects.equals(a.userId(), userId))
            .map(AttendanceResponse::status)
            .findFirst()
            .orElse(null);
    return new MeetingDetailResponse(
        meeting.getId(),
        meeting.getGroup().getId(),
        meeting.getGroup().getName(),
        meeting.getStatus(),
        meeting.getMeetingAt(),
        meeting.getPlace().getName(),
        meeting.getPlace().getLatitude(),
        meeting.getPlace().getLongitude(),
        meeting.getArrivalRule().getGraceMinutes(),
        meeting.getArrivalRule().getAbsentAfterMinutes(),
        meeting.getArrivalRule().getArrivalRadiusM(),
        meeting.getFineRule().getFineType(),
        meeting.getFineRule().getFineAmount(),
        meeting.getFineRule().getPenaltyText(),
        myStatus,
        attendances);
  }
}
