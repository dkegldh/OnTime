package com.ontime.user.dto;

import com.ontime.user.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
    @NotBlank @Size(max = User.NICKNAME_MAX_LENGTH) String nickname) {}
