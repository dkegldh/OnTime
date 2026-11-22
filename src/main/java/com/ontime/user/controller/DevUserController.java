package com.ontime.user.controller;

import com.ontime.user.dto.UserCreateRequest;
import com.ontime.user.dto.UserResponse;
import com.ontime.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증(Phase 4) 전까지 쓰는 개발용 사용자 API. local 프로필에서만 등록된다. */
@Tag(name = "Dev User", description = "개발용 임시 사용자 (Phase 4에서 제거)")
@Profile("local")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dev/users")
public class DevUserController {

  private final UserService userService;

  @Operation(summary = "개발용 사용자 생성")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse create(@Valid @RequestBody UserCreateRequest request) {
    return userService.create(request);
  }

  @Operation(summary = "개발용 사용자 목록")
  @GetMapping
  public List<UserResponse> findAll() {
    return userService.findAll();
  }
}
