package com.ontime.global.config;

import com.ontime.global.auth.LoginUser;
import com.ontime.global.auth.LoginUserArgumentResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

  private static final String USER_ID_SCHEME = "X-User-Id";

  static {
    // @LoginUser 파라미터는 요청 값이 아니므로 문서에서 숨긴다.
    SpringDocUtils.getConfig().addAnnotationsToIgnore(LoginUser.class);
  }

  @Bean
  public OpenAPI openApi() {
    // Phase 4(JWT) 전까지 Authorize 버튼에서 X-User-Id 헤더를 입력한다.
    SecurityScheme userIdHeader =
        new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY)
            .in(SecurityScheme.In.HEADER)
            .name(LoginUserArgumentResolver.USER_ID_HEADER)
            .description("개발용 사용자 id (POST /api/v1/dev/users 로 생성)");
    return new OpenAPI()
        .info(new Info().title("OnTime API").version("v1"))
        .components(new Components().addSecuritySchemes(USER_ID_SCHEME, userIdHeader))
        .addSecurityItem(new SecurityRequirement().addList(USER_ID_SCHEME));
  }
}
