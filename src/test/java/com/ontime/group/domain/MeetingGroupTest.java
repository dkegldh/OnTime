package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingGroupTest {

  // 약속 일시 2026-12-01 09:00 KST = 2026-12-01 00:00 UTC
  private static final LocalDate MEETING_DATE = LocalDate.of(2026, 12, 1);
  private static final LocalTime MEETING_TIME = LocalTime.of(9, 0);
  private static final Instant MEETING_AT = Instant.parse("2026-12-01T00:00:00Z");

  private static MeetingGroup createAt(Instant now) {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        MEETING_DATE,
        MEETING_TIME,
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        now);
  }

  @Test
  @DisplayName("약속 일시(KST) 1초 전에 만들면 일회성 그룹이 생성된다")
  void createOneTime_oneSecondBeforeMeeting_createsOneTimeGroup() {
    // when
    MeetingGroup group = createAt(MEETING_AT.minusSeconds(1));

    // then
    assertThat(group.getType()).isEqualTo(GroupType.ONE_TIME);
    assertThat(group.getStartDate()).isEqualTo(MEETING_DATE);
    assertThat(group.getMeetingTime()).isEqualTo(MEETING_TIME);
    assertThat(group.getDeletedAt()).isNull();
  }

  @Test
  @DisplayName("약속 일시(KST)와 같은 시각에 만들면 MEETING_TIME_IN_PAST로 거절된다")
  void createOneTime_atMeetingTime_rejectedAsInPast() {
    assertThatThrownBy(() -> createAt(MEETING_AT))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_TIME_IN_PAST);
  }

  @Test
  @DisplayName("약속 일시(KST) 1초 뒤에 만들면 MEETING_TIME_IN_PAST로 거절된다")
  void createOneTime_oneSecondAfterMeeting_rejectedAsInPast() {
    assertThatThrownBy(() -> createAt(MEETING_AT.plusSeconds(1)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_TIME_IN_PAST);
  }

  @Test
  @DisplayName("약속 시각을 UTC가 아닌 KST로 해석한다: 09:00 KST가 지난 05:00 UTC에는 거절된다")
  void createOneTime_afterKstMeetingButBeforeUtcSameClock_rejectedAsInPast() {
    // given: 2026-12-01 05:00 UTC = 14:00 KST. 09:00을 UTC로 잘못 해석하면 아직 미래로 보인다.
    Instant now = Instant.parse("2026-12-01T05:00:00Z");

    // when & then
    assertThatThrownBy(() -> createAt(now))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MEETING_TIME_IN_PAST);
  }

  @Test
  @DisplayName("삭제하면 삭제 시각이 기록된다")
  void delete_withServerTime_recordsDeletedAt() {
    // given
    MeetingGroup group = createAt(MEETING_AT.minusSeconds(3600));
    Instant now = Instant.parse("2026-11-30T12:00:00Z");

    // when
    group.delete(now);

    // then
    assertThat(group.getDeletedAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("이름을 바꾸면 이름만 바뀌고 규칙은 그대로다")
  void rename_newName_changesNameOnly() {
    // given
    MeetingGroup group = createAt(MEETING_AT.minusSeconds(3600));

    // when
    group.rename("토요일 모임");

    // then
    assertThat(group.getName()).isEqualTo("토요일 모임");
    assertThat(group.getArrivalRule().getGraceMinutes()).isEqualTo(5);
    assertThat(group.getFineRule().getFineAmount()).isEqualTo(3000L);
  }
}
