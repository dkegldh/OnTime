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
 * 서버 시각을 받는다. (도착, 지각 판정은 다음 이슈들에서 추가)
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
    if (meeting.isLateAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    this.status = AttendanceStatus.MOVING;
    this.departedAt = now;
  }
}
