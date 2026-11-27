package com.ontime.meeting.dto;

import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.AttendanceStatus;
import java.time.Instant;

/** 멤버별 참석 상태. 회차 상세의 참석 목록과 출발/도착 응답에 쓴다. */
public record AttendanceResponse(
    Long userId,
    String nickname,
    String profileImageUrl,
    AttendanceStatus status,
    Instant departedAt,
    Instant arrivedAt) {

  /** 사용자를 같이 읽어 둔(fetch join) 참석 행에서 만든다. */
  public static AttendanceResponse from(Attendance attendance) {
    return new AttendanceResponse(
        attendance.getUser().getId(),
        attendance.getUser().getNickname(),
        attendance.getUser().getProfileImageUrl(),
        attendance.getStatus(),
        attendance.getDepartedAt(),
        attendance.getArrivedAt());
  }
}
