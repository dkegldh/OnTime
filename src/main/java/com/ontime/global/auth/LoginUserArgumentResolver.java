package com.ontime.global.auth;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@code X-User-Id} 헤더로 현재 사용자를 식별한다. Phase 4에서 JWT 기반으로 교체한다. */
@Component
@RequiredArgsConstructor
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

  public static final String USER_ID_HEADER = "X-User-Id";

  private final UserService userService;

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(LoginUser.class)
        && Long.class.equals(parameter.getParameterType());
  }

  @Override
  public Long resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Long userId = parseUserId(webRequest.getHeader(USER_ID_HEADER));
    if (!userService.exists(userId)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return userId;
  }

  private Long parseUserId(String header) {
    if (!StringUtils.hasText(header)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    long userId;
    try {
      userId = Long.parseLong(header.trim());
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    if (userId <= 0) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return userId;
  }
}
