package com.ontime.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 초대 코드로 그룹에 참여한다. 앞뒤 공백과 대소문자는 구분하지 않는다. */
public record JoinGroupRequest(@NotBlank @Size(max = 20) String inviteCode) {

  public String normalizedCode() {
    return inviteCode.trim().toUpperCase(Locale.ROOT);
  }
}
