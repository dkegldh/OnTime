package com.ontime.group.dto;

import com.ontime.group.domain.InviteCode;
import java.time.Instant;

public record InviteCodeResponse(String code, Instant expiresAt) {

  public static InviteCodeResponse from(InviteCode inviteCode) {
    return new InviteCodeResponse(inviteCode.getCode(), inviteCode.getExpiresAt());
  }
}
