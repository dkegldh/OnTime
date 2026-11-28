package com.ontime.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.FineType;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingTest {

  private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

  // 2026-12-04 19:00 KST = 2026-12-04T10:00:00Z
  private static final Instant MEETING_AT = Instant.parse("2026-12-04T10:00:00Z");

  private static MeetingGroup group(FineRule fineRule) {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역 11번 출구", 37.4979, 127.0276),
        LocalDate.of(2026, 12, 4),
        LocalTime.of(19, 0),
        ArrivalRule.of(5, 60, 200),
        fineRule,
        NOW);
  }

  @Test
  @DisplayName("일회성 회차는 KST 약속 일시를 UTC로 바꿔 저장하고 SCHEDULED로 시작한다")
  void oneTimeOf_convertsKstMeetingTimeAndStartsScheduled() {
    // when
    Meeting meeting = Meeting.oneTimeOf(group(FineRule.money(3000)));

    // then
    assertThat(meeting.getMeetingAt()).isEqualTo(MEETING_AT);
    assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
  }

  @Test
  @DisplayName("회차는 그룹의 장소, 지각 규칙, 벌금 규칙 값을 복사하고 같은 인스턴스를 공유하지 않는다")
  void oneTimeOf_copiesGroupRulesAsSeparateInstances() {
    // given
    MeetingGroup group = group(FineRule.money(3000));

    // when
    Meeting meeting = Meeting.oneTimeOf(group);

    // then
    assertThat(meeting.getPlace().getName()).isEqualTo("강남역 11번 출구");
    assertThat(meeting.getPlace().getLatitude()).isEqualTo(37.4979);
    assertThat(meeting.getPlace().getLongitude()).isEqualTo(127.0276);
    assertThat(meeting.getArrivalRule().getGraceMinutes()).isEqualTo(5);
    assertThat(meeting.getArrivalRule().getAbsentAfterMinutes()).isEqualTo(60);
    assertThat(meeting.getArrivalRule().getArrivalRadiusM()).isEqualTo(200);
    assertThat(meeting.getFineRule().getFineType()).isEqualTo(FineType.MONEY);
    assertThat(meeting.getFineRule().getFineAmount()).isEqualTo(3000L);
    assertThat(meeting.getPlace()).isNotSameAs(group.getPlace());
    assertThat(meeting.getArrivalRule()).isNotSameAs(group.getArrivalRule());
    assertThat(meeting.getFineRule()).isNotSameAs(group.getFineRule());
  }

  @Test
  @DisplayName("벌칙(TEXT) 그룹의 회차는 벌칙 문구와 금액 0을 복사한다")
  void oneTimeOf_textFine_copiesPenaltyTextAndZeroAmount() {
    // when
    Meeting meeting = Meeting.oneTimeOf(group(FineRule.text("커피 사기")));

    // then
    assertThat(meeting.getFineRule().getFineType()).isEqualTo(FineType.TEXT);
    assertThat(meeting.getFineRule().getFineAmount()).isZero();
    assertThat(meeting.getFineRule().getPenaltyText()).isEqualTo("커피 사기");
  }

  @Test
  @DisplayName("일회성 그룹에는 정기 회차를 만들 수 없다")
  void recurringOf_oneTimeGroup_rejected() {
    assertThatThrownBy(() -> Meeting.recurringOf(group(FineRule.money(3000)), MEETING_AT))
        .isInstanceOf(IllegalStateException.class);
  }
}
