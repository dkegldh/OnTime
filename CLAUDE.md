# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code가 매번 읽는 안내서입니다.
설계 전체는 `README.md`, 진행 상황은 `docs/ROADMAP.md` 를 봅니다.
여기에는 **모든 작업에 항상 필요한 지식**만 적습니다. 절차와 검토는 아래로 나뉘어 있습니다.

| 언제 | 무엇을 |
| --- | --- |
| 기능/버그 작업을 이슈부터 PR까지 진행할 때 | `/ship-issue` 스킬 |
| 테스트를 쓰거나 고칠 때 (서비스 코드를 바꾸면 항상) | `write-tests` 스킬 |
| 새 API/도메인 기능을 만들 때 | `new-endpoint` 스킬 |
| 구현을 마친 뒤 리뷰 | `code-reviewer`, `test-auditor` 에이전트 |
| 전체 테스트 실행 | `test-runner` 에이전트 |

`.claude/hooks/` 의 hook이 일부 규칙(Clock, `@Setter`, `@Disabled`, 원장 수정, 비밀값, push/merge 제한)을 자동으로 검사합니다.
hook이 막으면 우회하지 말고 메시지대로 고칩니다.

## 프로젝트

약속(친구 약속, 스터디, 정기 모임)에서 멤버의 출발/도착 상태를 공유하고,
약속 시간까지 도착하지 않은 멤버에게 미리 정한 벌금이나 벌칙을 자동으로 매기는 서비스의 **백엔드**입니다.
프론트엔드(React + TypeScript)는 별도 저장소이며, 이 저장소는 REST API만 제공합니다.

기능 개수보다 **동시성, 정합성, 테스트**를 제대로 보여주는 것이 목표입니다.

## 기술 스택

- Java 17 (Java 21 이상 문법 사용 금지: 가상 스레드, 레코드 패턴의 switch 분해 등)
- Spring Boot 4.1.x, Spring Framework 7, Spring Security 7, Hibernate 7
- PostgreSQL 18, Redis 7.4
- Gradle (Groovy DSL), Lombok
- jjwt 0.13.0, springdoc-openapi 3.x
- 테스트: JUnit 5, AssertJ, Mockito, Testcontainers

### Spring Boot 4 주의사항 (Boot 3 자료를 참고할 때 틀리기 쉬움)

- 스타터 이름: `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-client`
- 테스트 스타터가 기술별로 나뉨: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test` 등
- `@MockBean`, `@SpyBean` 없음 → `@MockitoBean`, `@MockitoSpyBean`
- Jackson 3: 패키지가 `tools.jackson.*` (어노테이션은 그대로 `com.fasterxml.jackson.annotation`)
- `RestTemplate` 대신 `RestClient`

## 자주 쓰는 명령

```bash
docker compose up -d          # PostgreSQL, Redis 실행
./gradlew bootRun             # 로컬 실행 (profile: local)
./gradlew test                # 전체 테스트 (Docker 필요: Testcontainers)
./gradlew test --tests "com.ontime.meeting.*"   # 특정 패키지만
./gradlew build               # 빌드 + 테스트
```

- Swagger: http://localhost:8080/swagger-ui.html
- 헬스 체크: http://localhost:8080/api/v1/health

## 패키지 구조

기본 패키지는 `com.ontime` 입니다. 도메인별로 나눕니다.

```
com.ontime
├── global
│   ├── config     # Security, JPA, Redis, Clock, Swagger 설정
│   ├── auth       # JWT, OAuth2 성공 핸들러, 인증 필터
│   ├── error      # ErrorCode, BusinessException, GlobalExceptionHandler
│   └── common     # BaseTimeEntity, HealthController
├── user           # User, SocialAccount
├── group          # MeetingGroup, GroupMember, 초대 코드
├── meeting        # Meeting, Attendance, 출발/도착, 지각 스케줄러
└── fine           # FineLedger, 벌금 요약/내역, 면제/납부
```

각 도메인 안은 `controller`, `service`, `repository`, `domain`, `dto` 로 나눕니다.
도메인 간 참조는 서비스 계층을 통해서만 합니다. 다른 도메인의 repository를 직접 주입하지 않습니다.

## 코드 규칙

- Google Java Style Guide를 따릅니다.

### 시간

- 현재 시각은 반드시 주입받은 `Clock` 으로 구합니다: `Instant.now(clock)`
- `LocalDateTime.now()`, `Instant.now()`, `System.currentTimeMillis()` 를 직접 호출하지 않습니다.
- `Clock` 빈은 마이크로초로 내림한 시각을 줍니다(`MicrosecondClock`). DB `TIMESTAMPTZ` 가 마이크로초 아래를 반올림해 판정 시각과 저장 시각이 어긋나는 것을 막습니다. `Clock.tick` 은 쓰지 않습니다. (JDK 17에서 1ms 미만 tick의 `millis()` 가 `/ by zero`)
- 엔티티의 시각 필드는 `Instant`, DB 타입은 `TIMESTAMPTZ` 입니다. 저장은 UTC, 표시는 프론트가 Asia/Seoul로 합니다.
- 날짜만 필요한 값(정기 모임 시작일 등)은 `LocalDate`, 만나는 시각은 `LocalTime` (KST 기준)입니다.

### 엔티티

- `@Setter` 를 쓰지 않습니다. 상태 변경은 의미 있는 이름의 메서드로 합니다. (예: `attendance.depart(now)`, `attendance.arrive(now, lat, lng)`)
- 생성은 정적 팩토리 메서드 또는 `@Builder` + `private` 생성자로 합니다.
- 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- 연관관계는 기본 `FetchType.LAZY`. 조회 성능(N+1)은 fetch join, `@BatchSize`, DTO 조회로 해결합니다.
- PK는 `@GeneratedValue(strategy = GenerationType.IDENTITY)`
- enum은 `@Enumerated(EnumType.STRING)`. DB에는 `VARCHAR` + `CHECK` 제약으로 저장합니다. PostgreSQL ENUM 타입은 쓰지 않습니다.
- 금액은 `long` (원 단위 정수). `double`, `float` 로 돈을 다루지 않습니다.
- 그룹 테이블 이름은 `meeting_groups` 입니다. (`groups` 사용 금지)

### API

- 경로는 `/api/v1/...`, 복수형 명사, kebab-case
- 요청/응답 DTO는 `record` 로 만들고, 엔티티를 컨트롤러 밖으로 반환하지 않습니다.
- 요청 DTO는 Bean Validation(`@NotBlank`, `@Positive` 등)으로 검증합니다.
- 에러는 `BusinessException(ErrorCode.XXX)` 를 던지고, 응답 형식은 항상 `{ "code": "...", "message": "..." }` 입니다.
- 새 에러는 `ErrorCode` enum에 추가합니다. 메시지는 사용자에게 그대로 보여줄 한국어 문장(해요체)입니다.
- 목록 조회는 커서 페이지네이션(`cursor`, `size`)을 기본으로 합니다.

### 트랜잭션

- 서비스 클래스에 `@Transactional(readOnly = true)` 를 붙이고, 쓰기 메서드에만 `@Transactional` 을 붙입니다.
- 컨트롤러에는 트랜잭션을 걸지 않습니다. `spring.jpa.open-in-view=false` 입니다.

## 도메인 규칙 (반드시 지킬 것)

### 참석 상태 전이

```
READY  → MOVING        출발 버튼
READY  → ARRIVED       도착 버튼 (반경 안, 약속시간 + 유예 이전)
MOVING → ARRIVED       도착 버튼 (반경 안, 약속시간 + 유예 이전)
READY  → EXCUSED       빠지기 승인 (약속 24시간 전까지)
READY/MOVING → LATE    스케줄러: 약속시간 + 유예 경과
LATE   → LATE_ARRIVED  도착 버튼 (반경 안)
READY/MOVING → LATE_ARRIVED  도착 버튼 (반경 안, 약속시간 + 유예 경과). 스케줄러가 아직 LATE로 안 바꿨어도 서버 시각으로 지각 판정
LATE   → ABSENT        스케줄러: 약속시간 + absent_after_minutes 경과
```

- 위에 없는 전이는 `INVALID_STATE` 로 거절합니다. 전이 검사는 `Attendance` 엔티티 안에 둡니다.
- 약속시간 + `absent_after_minutes` 정각부터는 상태와 관계없이 도착을 거절합니다(`INVALID_STATE`). 스케줄러가 ABSENT로 바꿀 대상입니다.
- 출발/도착 버튼은 약속 3시간 전부터 허용합니다. 그 전이면 `MEETING_NOT_OPEN`.
- 도착은 약속 장소와의 거리를 하버사인 공식으로 계산해서 `arrival_radius_m` 이내일 때만 인정합니다. 밖이면 `ARRIVE_TOO_FAR`.
- 지각 판정은 **서버 시각** 기준입니다. 클라이언트가 보낸 시각을 믿지 않습니다.
- `attendances` 는 `@Version` 낙관적 락을 씁니다. 도착 버튼과 스케줄러가 동시에 같은 행을 바꿀 수 있습니다.

### 회차와 규칙 스냅샷

- 회차(`Meeting`)를 만들 때 그룹의 장소, 유예시간, 벌금 규칙을 **복사**해 저장합니다.
- 그룹 규칙을 바꾸면 아직 시작하지 않은(SCHEDULED) 회차에만 반영합니다. ONGOING, CLOSED 회차는 절대 바꾸지 않습니다.

### 벌금 원장 (`fine_ledger`)

- **append-only** 입니다. 원장 행을 UPDATE 하거나 DELETE 하는 코드를 쓰지 않습니다. (엔티티 필드 변경으로 인한 dirty checking UPDATE도 포함)
- 면제는 원래 행을 고치지 않고 `type = WAIVE`, `ref_ledger_id = 원래 행 id` 인 행을 추가합니다.
- 잔액은 저장하지 않고 계산합니다: `CHARGE 합 − WAIVE 합 − PAYMENT 합`
- 중복 부과는 DB 부분 유니크 인덱스로 막습니다. 애플리케이션 검사만 믿지 않습니다.
- 벌칙(TEXT) 그룹도 원장에 `amount = 0` 으로 기록해 횟수를 셉니다.
- 지각/결석 벌금은 결과가 **확정될 때 참석 행당 1회만** 부과합니다. `LATE_ARRIVED` 는 `fine_amount`, `ABSENT` 는 `fine_amount × 2` 입니다. `LATE` 인 동안은 부과하지 않습니다.
- 부과는 스케줄러만 합니다. 확정됐는데 지각/결석 CHARGE가 없는 행을 찾아 부과합니다. (스케줄러보다 먼저 도착해 LATE를 거치지 않은 `LATE_ARRIVED` 포함)
- 원장 행에 사유(`LATE` / `ABSENT`)를 기록합니다. 벌칙(TEXT) 그룹은 요약에서 결석 1건을 벌칙 2회로 셉니다.

### 스케줄러

- 지각 처리 스케줄러는 1분마다 돕니다. 여러 인스턴스에서 동시에 돌 수 있다고 가정하고 작성합니다. (ShedLock + DB 제약으로 이중 방어)
- 한 회차 처리 중 실패가 다른 회차 처리를 막지 않도록 회차 단위로 트랜잭션을 나눕니다.

## 테스트 (요약)

서비스 코드를 작성하거나 수정할 때는 **그 동작이 깨지면 실패하는 테스트**를 같이 씁니다. 자세한 기준과 예시는 `write-tests` 스킬에 있습니다.

- `./gradlew test` 가 전부 통과해야 작업이 끝난 것입니다.
- 테스트를 통과시키려고 기존 테스트를 지우거나, 검증을 약하게 바꾸거나, `@Disabled` 를 붙이지 않습니다. 기존 테스트가 실패하면 원인을 먼저 알려줍니다.
- 작업을 마칠 때 어떤 테스트를 추가했고, 각각 어떤 규칙을 검증하는지 짧게 요약합니다.

## 하지 말 것

- 비밀값(OAuth client secret, JWT secret, DB 비밀번호)을 코드나 커밋되는 설정 파일에 적지 않습니다. 환경 변수(`${...}`)로 받습니다.
- `.env` 를 커밋하지 않습니다.
- 운영 설정에서 `ddl-auto: update` / `create` 를 쓰지 않습니다. 스키마가 안정되면 Flyway로 전환합니다.
- 요청받지 않은 의존성을 `build.gradle` 에 추가하지 않습니다. 필요하면 먼저 이유와 함께 제안합니다.
- 설계(README의 ERD, API 명세, 상태 흐름)와 다르게 구현해야 할 것 같으면, 구현 전에 먼저 알려주고 README도 함께 고칩니다.
- 이슈 범위를 벗어난 수정이 필요하면, 같이 고치지 말고 먼저 알려줍니다.
- `main`, `develop` 에 직접 커밋/push하지 않고, PR을 머지하지 않고, force push하지 않습니다.
