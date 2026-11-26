package com.ontime.meeting.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.group.domain.ArrivalRule;
import com.ontime.group.domain.FineRule;
import com.ontime.group.domain.MeetingGroup;
import com.ontime.group.domain.Place;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 약속 한 번(회차).
 *
 * <p>만들 때 그룹의 장소, 지각/결석 규칙, 벌금 규칙을 복사(스냅샷)해 둔다. 그룹 규칙이 나중에 바뀌어도 이미 시작한 회차의 판정
 * 기준은 바뀌지 않는다.
 */
@Getter
@Entity
@Table(
    name = "meetings",
    // 그룹의 회차 목록(group_id 조건, id 오름차순)용.
    indexes = @Index(name = "idx_meetings_group_id", columnList = "group_id, id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting extends BaseTimeEntity {

  /** 출발/도착 버튼은 약속 이 시간 전부터 누를 수 있다. */
  public static final Duration BUTTON_OPEN_BEFORE = Duration.ofHours(3);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false)
  private MeetingGroup group;

  /** 약속 일시. 지각 판정의 기준이다. */
  @Column(nullable = false)
  private Instant meetingAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MeetingStatus status;

  @Embedded private Place place;

  @Embedded private ArrivalRule arrivalRule;

  @Embedded private FineRule fineRule;

  private Meeting(MeetingGroup group, Instant meetingAt) {
    this.group = group;
    this.meetingAt = meetingAt;
    this.status = MeetingStatus.SCHEDULED;
    this.place = group.getPlace().copy();
    this.arrivalRule = group.getArrivalRule().copy();
    this.fineRule = group.getFineRule().copy();
  }

  /** 일회성 그룹의 유일한 회차를 만든다. 그룹의 약속 일시와 규칙을 복사한다. */
  public static Meeting oneTimeOf(MeetingGroup group) {
    return new Meeting(group, group.oneTimeMeetingAt());
  }

  /** 출발/도착 버튼을 누를 수 있는 시각인지. 약속 3시간 전 정각부터 열린다. */
  public boolean isButtonOpenAt(Instant now) {
    return !now.isBefore(meetingAt.minus(BUTTON_OPEN_BEFORE));
  }

  /** 지각이 시작되는 시각. 약속시간 + 유예시간 정각부터 지각이다. */
  private Instant lateFrom() {
    return meetingAt.plus(Duration.ofMinutes(arrivalRule.getGraceMinutes()));
  }

  /** 서버 시각 {@code now} 가 지각 시작 시각(약속시간 + 유예) 이후인지. 정각을 포함한다. */
  public boolean isLateAt(Instant now) {
    return !now.isBefore(lateFrom());
  }
}
