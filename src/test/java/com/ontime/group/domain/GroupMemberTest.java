package com.ontime.group.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.user.domain.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupMemberTest {

  private static MeetingGroup group() {
    return MeetingGroup.createOneTime(
        "금요일 모임",
        Place.of("강남역", 37.4979, 127.0276),
        LocalDate.of(2026, 12, 31),
        LocalTime.of(19, 0),
        ArrivalRule.of(5, 60, 200),
        FineRule.money(3000),
        Instant.parse("2026-11-25T03:00:00Z"));
  }

  @Test
  @DisplayName("방장은 나갈 수 없어 OWNER_CANNOT_LEAVE로 거절된다")
  void validateCanLeave_owner_rejected() {
    // given
    GroupMember owner = GroupMember.owner(group(), User.create("방장"));

    // when & then
    assertThatThrownBy(owner::validateCanLeave)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.OWNER_CANNOT_LEAVE);
  }

  @Test
  @DisplayName("일반 멤버는 나갈 수 있다")
  void validateCanLeave_member_passes() {
    // given
    GroupMember member = GroupMember.member(group(), User.create("멤버"));

    // when & then
    assertThatCode(member::validateCanLeave).doesNotThrowAnyException();
  }
}
