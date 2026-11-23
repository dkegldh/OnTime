package com.ontime.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 예시: 통합 테스트 공통 부모. 모든 통합 테스트가 상속해서 컨테이너를 공유한다.
 *
 * <p>- H2 대신 실제 PostgreSQL 18 (부분 유니크 인덱스, 배열 타입이 PostgreSQL 전용)
 *
 * <p>- static 컨테이너 → 테스트 클래스 간 재사용 (JVM 하나당 한 번만 기동)
 *
 * <p>- {@code @ServiceConnection} 이 datasource / redis 접속 정보를 자동 주입
 *
 * <p>주의: Testcontainers 2.x 기준 패키지다. 1.x를 쓰면 PostgreSQLContainer 는
 * {@code org.testcontainers.containers.PostgreSQLContainer<?>} 이다. build.gradle 의 버전에 맞춘다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestSupport {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
}
