package com.ontime.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/api/v1/health")
            .permitAll()
            // Phase 4(JWT) 전까지 API 인증은 @LoginUser(X-User-Id)가 담당한다.
            .requestMatchers("/api/**")
            .permitAll()
            .anyRequest().authenticated());
    return http.build();
  }
}
