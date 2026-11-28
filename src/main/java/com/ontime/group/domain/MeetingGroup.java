package com.ontime.group.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "meeting_groups",
    // 일회성은 기간·요일이 없고, 정기 모임은 둘 다 있으며 종료일이 시작일 이후다.
    check =
        @CheckConstraint(
            name = "chk_meeting_groups_schedule",
            constraint =
                "(type = 'ONE_TIME' and end_date is null and days_of_week is null)"
                    + " or (type = 'RECURRING' and end_date is not null and end_date >= start_date"
                    + " and days_of_week between 1 and "
                    + Weekdays.ALL_BITS
                    + ")"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingGroup extends BaseTimeEntity {

  public static final int NAME_MAX_LENGTH = 30;

  /** 정기 모임 기간의 최대 일수. 시작일과 종료일을 모두 센다. 회차를 생성할 때 모두 만들기 때문에 상한을 둔다. */
  public static final int RECURRING_MAX_DAYS = 90;

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

  /** 일회성 약속이면 약속 날짜, 정기 모임이면 기간 시작일 (KST). */
  @Column(nullable = false)
  private LocalDate startDate;

  /** 정기 모임 기간 종료일 (KST, 포함). 일회성은 null. */
  private LocalDate endDate;

  /** 정기 모임 요일. 일회성은 null. */
  @Convert(converter = WeekdaysConverter.class)
  @Column(name = "days_of_week")
  private Weekdays daysOfWeek;

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
      LocalDate endDate,
      Weekdays daysOfWeek,
      LocalTime meetingTime,
      ArrivalRule arrivalRule,
      FineRule fineRule) {
    this.name = name;
    this.type = type;
    this.place = place;
    this.startDate = startDate;
    this.endDate = endDate;
    this.daysOfWeek = daysOfWeek;
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
    if (!toInstant(meetingDate, meetingTime).isAfter(now)) {
      throw new BusinessException(ErrorCode.MEETING_TIME_IN_PAST);
    }
    return new MeetingGroup(
        name,
        GroupType.ONE_TIME,
        place,
        meetingDate,
        null,
        null,
        meetingTime,
        arrivalRule,
        fineRule);
  }

  /**
   * 정기 모임 그룹을 만든다. 기간(시작일~종료일, KST, 양 끝 포함) 안의 고른 요일마다 {@code meetingTime} 에 회차가 열린다.
   *
   * <p>종료일이 시작일보다 앞서거나 기간이 {@value #RECURRING_MAX_DAYS}일을 넘으면 {@code VALIDATION_FAILED}, 시작일이
   * 오늘(KST)보다 앞서면 {@code MEETING_TIME_IN_PAST}, 서버 시각 뒤에 열리는 회차가 하나도 없으면 {@code
   * NO_MEETING_IN_PERIOD}.
   *
   * @param now 서버 시각
   */
  public static MeetingGroup createRecurring(
      String name,
      Place place,
      LocalDate startDate,
      LocalDate endDate,
      Weekdays daysOfWeek,
      LocalTime meetingTime,
      ArrivalRule arrivalRule,
      FineRule fineRule,
      Instant now) {
    if (daysOfWeek == null
        || endDate == null
        || endDate.isBefore(startDate)
        || ChronoUnit.DAYS.between(startDate, endDate) + 1 > RECURRING_MAX_DAYS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    if (startDate.isBefore(LocalDate.ofInstant(now, MEETING_ZONE))) {
      throw new BusinessException(ErrorCode.MEETING_TIME_IN_PAST);
    }
    MeetingGroup group =
        new MeetingGroup(
            name,
            GroupType.RECURRING,
            place,
            startDate,
            endDate,
            daysOfWeek,
            meetingTime,
            arrivalRule,
            fineRule);
    if (group.recurringMeetingTimesAfter(now).isEmpty()) {
      throw new BusinessException(ErrorCode.NO_MEETING_IN_PERIOD);
    }
    return group;
  }

  /** 일회성 약속의 약속 일시. KST 날짜와 시각을 UTC 시각으로 바꾼다. */
  public Instant oneTimeMeetingAt() {
    return toInstant(startDate, meetingTime);
  }

  /**
   * 정기 모임의 회차 일시 중 {@code now} 보다 뒤인 것을 이른 순으로. 기간 안의 고른 요일마다 {@code meetingTime}(KST)
   * 하나씩이다. 서버 시각과 같거나 앞선 회차(오늘 이미 지난 시각)는 빼고, 이미 시작한 회차로 보는 기준과 같다.
   */
  public List<Instant> recurringMeetingTimesAfter(Instant now) {
    if (type != GroupType.RECURRING) {
      throw new IllegalStateException("정기 모임이 아닙니다: " + type);
    }
    List<Instant> times = new ArrayList<>();
    for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
      if (!daysOfWeek.contains(date.getDayOfWeek())) {
        continue;
      }
      Instant meetingAt = toInstant(date, meetingTime);
      if (meetingAt.isAfter(now)) {
        times.add(meetingAt);
      }
    }
    return times;
  }

  private static Instant toInstant(LocalDate date, LocalTime time) {
    return date.atTime(time).atZone(MEETING_ZONE).toInstant();
  }

  public void rename(String name) {
    this.name = name;
  }

  /** soft delete. 이미 삭제된 그룹은 조회 단계에서 걸러지므로 여기서 다시 검사하지 않는다. */
  public void delete(Instant now) {
    this.deletedAt = now;
  }
}
