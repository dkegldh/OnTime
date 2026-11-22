package com.ontime.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ontime.global.error.BusinessException;
import com.ontime.global.error.ErrorCode;
import com.ontime.user.service.UserService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;

@ExtendWith(MockitoExtension.class)
class LoginUserArgumentResolverTest {

  @Mock private UserService userService;

  private LoginUserArgumentResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new LoginUserArgumentResolver(userService);
  }

  @SuppressWarnings("unused")
  private static class Handler {
    void withLoginUser(@LoginUser Long userId) {}

    void withoutAnnotation(Long userId) {}

    void withLoginUserString(@LoginUser String userId) {}
  }

  private static MethodParameter parameterOf(String methodName, Class<?> type) throws Exception {
    Method method = Handler.class.getDeclaredMethod(methodName, type);
    return new MethodParameter(method, 0);
  }

  private static NativeWebRequest requestWithHeader(String userId) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (userId != null) {
      request.addHeader(LoginUserArgumentResolver.USER_ID_HEADER, userId);
    }
    return new ServletWebRequest(request);
  }

  private Long resolve(String header) throws Exception {
    return resolver.resolveArgument(
        parameterOf("withLoginUser", Long.class), null, requestWithHeader(header), null);
  }

  @Test
  @DisplayName("@LoginUser가 붙은 Long 파라미터를 지원한다")
  void supportsParameter_loginUserLong_returnsTrue() throws Exception {
    // given
    MethodParameter parameter = parameterOf("withLoginUser", Long.class);

    // when
    boolean supported = resolver.supportsParameter(parameter);

    // then
    assertThat(supported).isTrue();
  }

  @Test
  @DisplayName("@LoginUser가 없는 Long 파라미터는 지원하지 않는다")
  void supportsParameter_withoutAnnotation_returnsFalse() throws Exception {
    // given
    MethodParameter parameter = parameterOf("withoutAnnotation", Long.class);

    // when
    boolean supported = resolver.supportsParameter(parameter);

    // then
    assertThat(supported).isFalse();
  }

  @Test
  @DisplayName("@LoginUser가 붙어도 Long이 아니면 지원하지 않는다")
  void supportsParameter_nonLongType_returnsFalse() throws Exception {
    // given
    MethodParameter parameter = parameterOf("withLoginUserString", String.class);

    // when
    boolean supported = resolver.supportsParameter(parameter);

    // then
    assertThat(supported).isFalse();
  }

  @Test
  @DisplayName("존재하는 사용자 id 헤더면 그 id를 돌려준다")
  void resolveArgument_existingUser_returnsUserId() throws Exception {
    // given
    given(userService.exists(1L)).willReturn(true);

    // when
    Long userId = resolve("1");

    // then
    assertThat(userId).isEqualTo(1L);
  }

  @Test
  @DisplayName("X-User-Id 헤더가 없으면 UNAUTHORIZED로 거절한다")
  void resolveArgument_missingHeader_throwsUnauthorized() {
    assertThatThrownBy(() -> resolve(null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("X-User-Id 헤더가 빈 문자열이면 UNAUTHORIZED로 거절한다")
  void resolveArgument_blankHeader_throwsUnauthorized() {
    assertThatThrownBy(() -> resolve(" "))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("X-User-Id 헤더가 숫자가 아니면 UNAUTHORIZED로 거절한다")
  void resolveArgument_nonNumericHeader_throwsUnauthorized() {
    assertThatThrownBy(() -> resolve("abc"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("X-User-Id 헤더가 0이면 UNAUTHORIZED로 거절한다")
  void resolveArgument_zeroId_throwsUnauthorized() {
    assertThatThrownBy(() -> resolve("0"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("존재하지 않는 사용자 id면 UNAUTHORIZED로 거절한다")
  void resolveArgument_nonExistingUser_throwsUnauthorized() {
    // given
    given(userService.exists(99L)).willReturn(false);

    // when & then
    assertThatThrownBy(() -> resolve("99"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }
}
