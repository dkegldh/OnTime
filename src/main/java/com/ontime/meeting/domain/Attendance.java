package com.ontime.meeting.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차별 멤버 참석 상태.
 *
 * <p>상태 전이 검사는 이 엔티티의 메서드가 가진다. 허용되지 않은 전이는 {@code INVALID_STATE} 로 거절한다. 시각은 항상
 * 서버 시각을 받는다. (지각/결석 스케줄러 전이는 Phase 3에서 추가)
 */
@Getter
@Entity
@Table(
    name = "attendances",
    uniqueConstraints =
        @UniqueConstraint(
            name = Attendance.UNIQUE_MEETING_USER,
            columnNames = {"meeting_id", "user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attendance extends BaseTimeEntity {

  /** (meeting_id, user_id) 유니크 제약 이름. 한 회차에 같은 사용자의 참석 행은 하나다. */
  public static final String UNIQUE_MEETING_USER = "uk_attendances_meeting_user";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "meeting_id", nullable = false)
  private Meeting meeting;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AttendanceStatus status;

  private Instant departedAt;

  private Instant arrivedAt;

  /** 도착 버튼과 지각 스케줄러가 같은 행을 동시에 바꿀 수 있어 낙관적 락을 쓴다. */
  @Version private long version;

  private Attendance(Meeting meeting, User user) {
    this.meeting = meeting;
    this.user = user;
    this.status = AttendanceStatus.READY;
  }

  /** 회차에 멤버를 READY 상태로 등록한다. */
  public static Attendance ready(Meeting meeting, User user) {
    return new Attendance(meeting, user);
  }

  /**
   * 출발: {@code READY → MOVING}, 출발 시각을 기록한다.
   *
   * <p>READY가 아니면 {@code INVALID_STATE}, 약속 3시간 전보다 이르면 {@code MEETING_NOT_OPEN}, 약속시간 + 유예가
   * 지났으면(정각 포함) {@code INVALID_STATE}. 유예가 지난 READY는 지각 스케줄러가 LATE로 바꿀 대상이라, 스케줄러가 아직 돌지
   * 않았더라도 서버 시각만으로 거절한다.
   */
  public void depart(Instant now) {
    if (status != AttendanceStatus.READY) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (!meeting.isButtonOpenAt(now)) {
      throw new BusinessException(ErrorCode.MEETING_NOT_OPEN);
    }
    if (meeting.isDepartClosedAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    this.status = AttendanceStatus.MOVING;
    this.departedAt = now;
  }

  /**
   * 도착: 반경 안이면 {@code ARRIVED} 또는 {@code LATE_ARRIVED} 로 바꾸고 도착 시각을 기록한다.
   *
   * <ul>
   *   <li>READY/MOVING이고 약속시간 + 유예 전이면 {@code ARRIVED}
   *   <li>LATE이거나, 유예가 지난 READY/MOVING이면 {@code LATE_ARRIVED}. 유예가 지난 READY/MOVING은 지각 스케줄러가
   *       아직 돌지 않았더라도 LATE로 보고 {@code LATE → LATE_ARRIVED} 로 판정한다. (스케줄러가 먼저 돈 경우와 최종 상태가 같다)
   * </ul>
   *
   * <p>READY/MOVING/LATE가 아니면 {@code INVALID_STATE}, 약속 3시간 전보다 이르면 {@code MEETING_NOT_OPEN}, 결석 판정
   * 시각이 지났으면(정각 포함) 스케줄러가 ABSENT로 바꿀 대상이라 {@code INVALID_STATE}, 반경 밖이면 {@code ARRIVE_TOO_FAR}.
   */
  public void arrive(Instant now, double latitude, double longitude) {
    if (status != AttendanceStatus.READY
        && status != AttendanceStatus.MOVING
        && status != AttendanceStatus.LATE) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (!meeting.isButtonOpenAt(now)) {
      throw new BusinessException(ErrorCode.MEETING_NOT_OPEN);
    }
    if (meeting.isAbsentAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (!meeting.isWithinArrivalRadius(latitude, longitude)) {
      throw new BusinessException(ErrorCode.ARRIVE_TOO_FAR);
    }
    boolean late = status == AttendanceStatus.LATE || meeting.isLateAt(now);
    this.status = late ? AttendanceStatus.LATE_ARRIVED : AttendanceStatus.ARRIVED;
    this.arrivedAt = now;
  }
}
