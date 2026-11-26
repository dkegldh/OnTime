package com.ontime.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import com.ontime.user.domain.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttendanceTest {

  private static Meeting meeting() {
    return Meeting.oneTimeOf(
        MeetingGroup.createOneTime(
            "금요일 모임",
            Place.of("강남역", 37.4979, 127.0276),
            LocalDate.of(2026, 12, 4),
            LocalTime.of(19, 0),
            ArrivalRule.of(5, 60, 200),
            FineRule.money(3000),
            Instant.parse("2026-11-25T03:00:00Z")));
  }

  @Test
  @DisplayName("회차에 등록된 멤버는 출발/도착 시각 없이 READY로 시작한다")
  void ready_startsAsReadyWithoutDepartureOrArrival() {
    // when
    Attendance attendance = Attendance.ready(meeting(), User.create("멤버"));

    // then
    assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.READY);
    assertThat(attendance.getDepartedAt()).isNull();
    assertThat(attendance.getArrivedAt()).isNull();
  }
}
