package com.ontime.group.domain;

import com.ontime.global.common.BaseTimeEntity;
import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹 초대 코드. 그룹당 하나이고, 다시 발급하면 같은 행의 코드와 만료 시각을 바꿔서 이전 코드는 바로 쓸 수 없다.
 */
@Getter
@Entity
@Table(
    name = "invite_codes",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_invite_codes_group", columnNames = "group_id"),
      @UniqueConstraint(name = "uk_invite_codes_code", columnNames = "code")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode extends BaseTimeEntity {

  public static final int CODE_LENGTH = 8;
  public static final Duration VALIDITY = Duration.ofDays(7);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false)
  private MeetingGroup group;

  @Column(nullable = false, length = CODE_LENGTH)
  private String code;

  @Column(nullable = false)
  private Instant expiresAt;

  /** 동시에 재발급하면 나중 쪽을 실패시켜, 응답으로 받은 코드가 이미 무효인 상황을 막는다. */
  @Version private long version;

  private InviteCode(MeetingGroup group, String code, Instant expiresAt) {
    this.group = group;
    this.code = code;
    this.expiresAt = expiresAt;
  }

  /** 새 초대 코드를 발급한다. {@code now} 부터 {@link #VALIDITY} 동안 쓸 수 있다. */
  public static InviteCode issue(MeetingGroup group, String code, Instant now) {
    return new InviteCode(group, code, now.plus(VALIDITY));
  }

  /** 코드와 만료 시각을 새로 바꾼다. 이전 코드는 더 이상 찾을 수 없다. */
  public void reissue(String code, Instant now) {
    this.code = code;
    this.expiresAt = now.plus(VALIDITY);
  }

  /** 만료 시각 정각부터는 쓸 수 없다. */
  public void validateUsableAt(Instant now) {
    if (!now.isBefore(expiresAt)) {
      throw new BusinessException(ErrorCode.INVITE_CODE_EXPIRED);
    }
  }
}
