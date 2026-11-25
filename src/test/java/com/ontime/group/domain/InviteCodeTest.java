package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InviteCodeTest {

  private static final Instant ISSUED_AT = Instant.parse("2026-11-25T03:00:00Z");
  // 발급 7일 뒤
  private static final Instant EXPIRES_AT = Instant.parse("2026-12-02T03:00:00Z");

  private static MeetingGroup group() {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        LocalDate.of(2026, 12, 31),
        LocalTime.of(19, 0),
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        ISSUED_AT);
  }

  @Test
  @DisplayName("발급하면 7일 뒤에 만료된다")
  void issue_setsExpiryToSevenDaysLater() {
    // when
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", ISSUED_AT);

    // then
    assertThat(inviteCode.getCode()).isEqualTo("ABCD2345");
    assertThat(inviteCode.getExpiresAt()).isEqualTo(EXPIRES_AT);
  }

  @Test
  @DisplayName("만료 1초 전에는 쓸 수 있다")
  void validateUsableAt_oneSecondBeforeExpiry_passes() {
    // given
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", ISSUED_AT);

    // when & then
    assertThatCode(() -> inviteCode.validateUsableAt(EXPIRES_AT.minusSeconds(1)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("만료 시각 정각에는 INVITE_CODE_EXPIRED로 거절된다")
  void validateUsableAt_atExpiry_rejectedAsExpired() {
    // given
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", ISSUED_AT);

    // when & then
    assertThatThrownBy(() -> inviteCode.validateUsableAt(EXPIRES_AT))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVITE_CODE_EXPIRED);
  }

  @Test
  @DisplayName("만료 1초 뒤에는 INVITE_CODE_EXPIRED로 거절된다")
  void validateUsableAt_oneSecondAfterExpiry_rejectedAsExpired() {
    // given
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", ISSUED_AT);

    // when & then
    assertThatThrownBy(() -> inviteCode.validateUsableAt(EXPIRES_AT.plusSeconds(1)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVITE_CODE_EXPIRED);
  }

  @Test
  @DisplayName("다시 발급하면 코드가 바뀌고 재발급 시각부터 7일 뒤에 만료된다")
  void reissue_later_replacesCodeAndExtendsExpiry() {
    // given
    InviteCode inviteCode = InviteCode.issue(group(), "ABCD2345", ISSUED_AT);
    Instant reissuedAt = Instant.parse("2026-11-30T00:00:00Z");

    // when
    inviteCode.reissue("WXYZ6789", reissuedAt);

    // then
    assertThat(inviteCode.getCode()).isEqualTo("WXYZ6789");
    assertThat(inviteCode.getExpiresAt()).isEqualTo(Instant.parse("2026-12-07T00:00:00Z"));
  }
}
