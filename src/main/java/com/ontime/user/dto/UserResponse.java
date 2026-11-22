package com.ontime.user.dto;

import com.ontime.user.domain.User;

public record UserResponse(Long id, String nickname) {

  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getNickname());
  }
}
