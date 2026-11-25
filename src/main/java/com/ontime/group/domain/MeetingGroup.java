package com.ontime.group.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "meeting_groups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingGroup extends BaseTimeEntity {

  public static final int NAME_MAX_LENGTH = 30;

  /** 약속 날짜와 만나는 시각의 기준 시간대. */
  public static final ZoneId MEETING_ZONE = ZoneId.of("Asia/Seoul");

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private GroupType type;

  @Embedded private Place place;

  /** 일회성 약속이면 약속 날짜 (KST). */
  @Column(nullable = false)
  private LocalDate startDate;

  /** 만나는 시각 (KST). */
  @Column(nullable = false)
  private LocalTime meetingTime;

  @Embedded private ArrivalRule arrivalRule;

  @Embedded private FineRule fineRule;

  private Instant deletedAt;

  /**
   * 낙관적 락. 이름 수정과 삭제가 동시에 커밋되면, 먼저 읽은 상태로 전체 컬럼을 UPDATE 하는 쪽이 {@code deleted_at} 을
   * 되돌리는 lost update가 생기므로 나중에 커밋하는 쪽을 실패시킨다.
   */
  @Version private long version;

  private MeetingGroup(
      String name,
      GroupType type,
      Place place,
      LocalDate startDate,
      LocalTime meetingTime,
      ArrivalRule arrivalRule,
      FineRule fineRule) {
    this.name = name;
    this.type = type;
    this.place = place;
    this.startDate = startDate;
    this.meetingTime = meetingTime;
    this.arrivalRule = arrivalRule;
    this.fineRule = fineRule;
  }

  /**
   * 일회성 약속 그룹을 만든다.
   *
   * @param now 서버 시각. 약속 일시가 이 시각보다 뒤가 아니면 {@code MEETING_TIME_IN_PAST}
   */
  public static MeetingGroup createOneTime(
      String name,
      Place place,
      LocalDate meetingDate,
      LocalTime meetingTime,
      ArrivalRule arrivalRule,
      FineRule fineRule,
      Instant now) {
    Instant meetingAt = meetingDate.atTime(meetingTime).atZone(MEETING_ZONE).toInstant();
    if (!meetingAt.isAfter(now)) {
      throw new BusinessException(ErrorCode.MEETING_TIME_IN_PAST);
    }
    return new MeetingGroup(
        name, GroupType.ONE_TIME, place, meetingDate, meetingTime, arrivalRule, fineRule);
  }

  public void rename(String name) {
    this.name = name;
  }

  /** soft delete. 이미 삭제된 그룹은 조회 단계에서 걸러지므로 여기서 다시 검사하지 않는다. */
  public void delete(Instant now) {
    this.deletedAt = now;
  }
}
