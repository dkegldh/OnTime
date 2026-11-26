package com.ontime.meeting.domain;

/** 참석 상태. 전이 규칙은 {@link Attendance} 가 가진다. */
public enum AttendanceStatus {
  READY,
  MOVING,
  ARRIVED,
  LATE,
  LATE_ARRIVED,
  ABSENT,
  EXCUSED
}
