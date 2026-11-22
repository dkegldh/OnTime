package com.ontime.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 현재 요청한 사용자의 id({@code Long})를 컨트롤러 파라미터로 주입한다.
 *
 * <p>Phase 4 전까지는 {@code X-User-Id} 헤더로 식별하고, 이후 JWT 기반으로 교체한다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUser {}
