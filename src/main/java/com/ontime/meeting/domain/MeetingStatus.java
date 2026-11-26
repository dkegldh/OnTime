package com.ontime.meeting.domain;

/** 회차 상태. 지금은 SCHEDULED 만 만들고, 전이는 Phase 3 스케줄러에서 다룬다. */
public enum MeetingStatus {
  SCHEDULED,
  ONGOING,
  CLOSED
}
