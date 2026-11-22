# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code가 매번 읽는 안내서입니다.
설계 전체는 `README.md` 를 보고, 여기에는 코드를 쓸 때 지켜야 할 것만 적습니다.

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
- `LocalDateTime.now()`, `Instant.now()`, `System.currentTimeMillis()` 를 직접 호출하지 않습니다. 테스트에서 약속 시간을 옮길 수 없게 됩니다.
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
- 새 에러가 필요하면 `ErrorCode` enum에 추가합니다. 메시지는 사용자에게 그대로 보여줄 한국어 문장(해요체)으로 씁니다.
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
LATE   → ABSENT        스케줄러: 약속시간 + absent_after_minutes 경과
```

- 위에 없는 전이는 `INVALID_STATE` 로 거절합니다. 전이 검사는 `Attendance` 엔티티 안에 둡니다.
- 출발/도착 버튼은 약속 3시간 전부터 허용합니다. 그 전이면 `MEETING_NOT_OPEN`.
- 도착은 약속 장소와의 거리를 하버사인 공식으로 계산해서 `arrival_radius_m` 이내일 때만 인정합니다. 밖이면 `ARRIVE_TOO_FAR`.
- 지각 판정은 **서버 시각** 기준입니다. 클라이언트가 보낸 시각을 믿지 않습니다.
- `attendances` 는 `@Version` 낙관적 락을 씁니다. 도착 버튼과 스케줄러가 동시에 같은 행을 바꿀 수 있습니다.

### 회차와 규칙 스냅샷

- 회차(`Meeting`)를 만들 때 그룹의 장소, 유예시간, 벌금 규칙을 **복사**해 저장합니다.
- 그룹 규칙을 바꾸면 아직 시작하지 않은(SCHEDULED) 회차에만 반영합니다. ONGOING, CLOSED 회차는 절대 바꾸지 않습니다.

### 벌금 원장 (`fine_ledger`)

- **append-only** 입니다. 원장 행을 UPDATE 하거나 DELETE 하는 코드를 쓰지 않습니다.
- 면제는 원래 행을 고치지 않고 `type = WAIVE`, `ref_ledger_id = 원래 행 id` 인 행을 추가합니다.
- 잔액은 저장하지 않고 계산합니다: `CHARGE 합 − WAIVE 합 − PAYMENT 합`
- 중복 부과는 DB 부분 유니크 인덱스로 막습니다. 애플리케이션 검사만 믿지 않습니다.
- 벌칙(TEXT) 그룹도 원장에 `amount = 0` 으로 기록해 횟수를 셉니다.

### 스케줄러

- 지각 처리 스케줄러는 1분마다 돕니다. 여러 인스턴스에서 동시에 돌 수 있다고 가정하고 작성합니다. (ShedLock + DB 제약으로 이중 방어)
- 한 회차 처리 중 실패가 다른 회차 처리를 막지 않도록 회차 단위로 트랜잭션을 나눕니다.

## 테스트

서비스 코드를 작성하거나 수정할 때는 **의미있는 테스트 코드를 같이 작성합니다.** 테스트 없이 기능 작업을 끝내지 않습니다.

### 의미있는 테스트란

**그 동작이 깨지면 실패하는 테스트**입니다. 테스트를 쓰기 전에 "이 메서드의 규칙이 틀리게 바뀌면 이 테스트가 잡아내는가?"를 확인합니다.

서비스 메서드마다 아래를 확인합니다.

- **정상 흐름**: 기대한 상태 변경과 반환값이 맞는지
- **규칙과 분기**: 권한(방장/멤버), 상태 전이, 금액 계산처럼 코드에 있는 `if` 하나하나
- **경계값**: 시간과 금액이 걸린 규칙은 기준점과 그 전후 (예: 유예시간 정각, 1초 전, 1초 후 / 반경 200m 안과 밖). 상태 전이, 지각 판정, 잔액 계산은 반드시 포함합니다.
- **실패 흐름**: 잘못된 요청이 정확한 `ErrorCode` 로 거절되는지, 그리고 거절된 뒤 데이터가 바뀌지 않았는지

### 의미없는 테스트 (쓰지 않음)

- getter/setter, 생성자, DTO 변환만 확인하는 테스트
- mock이 호출됐는지(`verify`)만 확인하고 결과나 상태는 보지 않는 테스트
- 구현 코드를 그대로 따라 써서 기대값을 계산하는 테스트 (기대값은 손으로 계산한 상수로 씁니다)
- 예외가 "발생하는지"만 보고 어떤 `ErrorCode` 인지는 확인하지 않는 테스트

### 작성 방식

- 테스트 하나는 동작 하나만 검증합니다.
- given / when / then 주석으로 구분합니다.
- 메서드 이름은 영어로 `대상_조건_결과` 형식으로 쓰고, `@DisplayName` 에 한국어 설명을 붙입니다.
  ```java
  @Test
  @DisplayName("유예시간 정각에 도착하면 ARRIVED가 된다")
  void arrive_atGraceDeadline_becomesArrived() { ... }
  ```
- 시간은 `Clock.fixed(...)` 로 고정합니다.
- 단위 테스트: 도메인 엔티티와 서비스 (Mockito)
- 통합 테스트: DB 제약이나 쿼리가 걸린 동작은 `@SpringBootTest` + Testcontainers(PostgreSQL, Redis)로 검증합니다. H2를 쓰지 않습니다. (부분 인덱스, 배열 타입이 PostgreSQL 전용)
- 동시성 테스트: `ExecutorService` + `CountDownLatch` 로 같은 요청을 동시에 보내서 검증합니다.

### 완료 기준

- `./gradlew test` 가 전부 통과해야 작업이 끝난 것입니다.
- 테스트를 통과시키려고 기존 테스트를 지우거나, 검증을 약하게 바꾸거나, `@Disabled` 를 붙이지 않습니다. 기존 테스트가 실패하면 원인을 먼저 알려줍니다.
- 작업을 마칠 때 어떤 테스트를 추가했고, 각각 어떤 규칙을 검증하는지 짧게 요약합니다.

## 하지 말 것

- 비밀값(OAuth client secret, JWT secret, DB 비밀번호)을 코드나 커밋되는 설정 파일에 적지 않습니다. 환경 변수(`${...}`)로 받습니다.
- `.env` 를 커밋하지 않습니다.
- 운영 설정에서 `ddl-auto: update` / `create` 를 쓰지 않습니다. 스키마가 안정되면 Flyway로 전환합니다.
- 요청받지 않은 의존성을 `build.gradle` 에 추가하지 않습니다. 필요하면 먼저 이유와 함께 제안합니다.
- 설계(README의 ERD, API 명세, 상태 흐름)와 다르게 구현해야 할 것 같으면, 구현 전에 먼저 알려주고 README도 함께 고칩니다.

## 커밋 메시지

Conventional Commits 형식, 설명은 한국어로 씁니다.

```
feat: 도착 버튼 반경 검증 추가
fix: 유예시간 정각 도착이 지각으로 처리되던 문제 수정
refactor: 지각 판정 로직을 Attendance 엔티티로 이동
test: 벌금 면제 후 잔액 계산 테스트 추가
docs: README API 명세에 excuse 엔드포인트 추가
chore: ShedLock 의존성 추가
```

## 작업 방식

모든 작업은 **이슈 → 브랜치 → 구현 → 셀프 리뷰 → 테스트 → PR** 순서로 진행합니다.

### 1. 이슈 작성

- `.github/ISSUE_TEMPLATE/` 의 템플릿에 맞춰 이슈 내용을 작성합니다.
- **이슈를 만들기 전에 제목과 본문을 먼저 보여주고 확인을 받습니다.** 확인 후 `gh issue create` 로 생성합니다.
- 생성된 이슈 번호를 확인합니다.

### 2. 브랜치 생성

항상 최신 `develop` 에서 새 브랜치를 만듭니다.

```bash
git checkout develop
git pull origin develop
git checkout -b {type}/{이슈번호}-{작업-요약}
```

| type | 용도 | 예시 |
| --- | --- | --- |
| feature | 새 기능 | `feature/1-entity-create` |
| fix | 버그 수정 | `fix/12-grace-time-boundary` |
| refactor | 동작 변화 없는 구조 개선 | `refactor/15-attendance-state` |
| test | 테스트만 추가/수정 | `test/18-fine-balance` |
| docs | 문서 | `docs/20-api-spec` |
| chore | 설정, 의존성, 빌드 | `chore/3-docker-compose` |

- 작업 요약은 영어 소문자 kebab-case로 3~4단어 이내로 씁니다.
- `main`, `develop` 에 직접 커밋하거나 push하지 않습니다.

### 3. 구현과 커밋

- 커밋은 논리적인 단위로 나눕니다. 커밋 메시지는 "커밋 메시지" 규칙을 따르고, 끝에 이슈 번호를 붙입니다. (예: `feat: 도착 반경 검증 추가 (#7)`)
- 이슈 범위를 벗어난 수정이 필요하면, 같이 고치지 말고 먼저 알려줍니다.

### 4. 셀프 코드 리뷰

`git diff develop...HEAD` 로 변경 전체를 다시 읽고 아래를 확인합니다.

- [ ] 이슈에 적힌 요구사항을 모두 구현했는가
- [ ] CLAUDE.md의 코드 규칙과 도메인 규칙을 지켰는가 (특히 `Clock` 사용, 원장 append-only, 상태 전이)
- [ ] README의 ERD, API 명세와 다르게 구현한 곳은 없는가 (다르면 README도 수정)
- [ ] 디버그용 출력, 주석 처리된 코드, 쓰지 않는 import가 남아 있지 않은가
- [ ] 비밀값이나 `.env` 가 커밋에 포함되지 않았는가
- [ ] N+1 쿼리, 트랜잭션 범위 등 성능과 정합성 문제는 없는가

발견한 문제는 고친 뒤 다음 단계로 넘어갑니다.

### 5. 테스트

- `./gradlew test` 로 **전체 테스트**를 실행합니다. ("테스트" 섹션의 완료 기준을 따릅니다)
- 실패하면 PR을 만들지 않고 원인을 먼저 알려줍니다.

### 6. PR 작성

- `git push -u origin {브랜치명}` 후 `.github/PULL_REQUEST_TEMPLATE.md` 에 맞춰 `gh pr create --base develop` 으로 PR을 만듭니다.
- PR 본문에 관련 이슈(`Closes #이슈번호`), 셀프 리뷰 결과, 추가한 테스트 요약을 적습니다.
- **PR 머지는 하지 않습니다.** 리뷰와 머지는 사람이 합니다.
- force push(`git push -f`)는 하지 않습니다.

> 참고: PR이 기본 브랜치가 아닌 `develop` 으로 머지되면 `Closes #번호` 로는 이슈가 자동으로 닫히지 않습니다. `develop` → `main` 으로 머지할 때 닫히거나, 직접 닫아야 합니다.

## 현재 진행 상황

> 단계가 끝날 때마다 체크하고, 현재 단계를 갱신합니다.
> 각 하위 항목은 GitHub 이슈 하나 단위입니다.

**현재 단계: Phase 1**

### Phase 0. 초기 세팅 ✅
- [x] Docker Compose(PostgreSQL, Redis), 공통 예외 처리, Swagger, 헬스 체크

### Phase 1. 기본 CRUD (인증 없이)
목표: JPA 엔티티, 연관관계, DTO, 검증, 예외 처리, 기본 테스트에 익숙해지기
- [x] 임시 사용자: 개발용 사용자 생성 API, `X-User-Id` 헤더 + `@LoginUser` ArgumentResolver
- [ ] 그룹 CRUD: 생성(일회성 약속), 목록, 상세, 수정, 삭제
- [ ] 그룹 참여: 초대 코드 생성, 코드로 참여, 나가기, 멤버 목록
- [ ] 권한: 방장 전용 기능(수정, 삭제) 검사

완료 조건: Swagger에서 그룹을 만들고, 다른 사용자로 초대 코드 참여까지 할 수 있다.

### Phase 2. 핵심 도메인 로직
목표: 상태 전이를 엔티티에 담기, `Clock` 으로 시간 테스트하기
- [ ] 회차 상세 조회 (멤버별 상태)
- [ ] 출발 (READY → MOVING), 버튼 활성 시간(약속 3시간 전) 검사
- [ ] 도착 (반경 검증, 지각 여부 판정 → ARRIVED / LATE_ARRIVED)
- [ ] 상태 전이 경계값 테스트 (유예시간 정각, 1초 전후)

완료 조건: 일회성 약속에서 출발, 도착, 지각 후 도착이 규칙대로 동작한다.

### Phase 3. 정기 모임과 벌금
목표: 배치성 로직, 원장 설계, 집계 쿼리
- [ ] 정기 모임 생성 (기간, 요일 배열 → 회차 일괄 생성, 규칙 스냅샷)
- [ ] 지각/결석 처리 스케줄러 (`@Scheduled`, 회차 단위 트랜잭션)
- [ ] 벌금 원장 부과 (append-only, 부분 유니크 인덱스)
- [ ] 벌금 요약, 원장 내역 (커서 페이지네이션)

완료 조건: 정기 모임에서 지각한 멤버에게 벌금이 자동으로 쌓이고 요약에 보인다.

### Phase 4. 인증
목표: OAuth2, JWT, Redis
- [ ] 구글, 카카오 OAuth2 로그인
- [ ] JWT 발급/재발급, 리프레시 토큰 Redis 저장, 로그아웃
- [ ] `@LoginUser` 를 JWT 기반으로 교체, `X-User-Id` 와 개발용 사용자 API 제거
- [ ] `GET /me`

완료 조건: 소셜 로그인 후 받은 토큰으로 Phase 1~3 기능을 그대로 쓸 수 있다.

### Phase 5. 운영 기능
- [ ] 미리 빠지기 신청과 승인
- [ ] 벌금 면제, 납부 확인
- [ ] 그룹 규칙 수정 (SCHEDULED 회차에만 반영)

### Phase 6. 심화
목표: 포트폴리오 트러블슈팅 글 만들기 (문제 재현 → 원인 → 해결 → 수치)
- [ ] 동시성: 도착 버튼 vs 스케줄러 낙관적 락, 동시성 테스트
- [ ] 중복 요청 방지: `Idempotency-Key` + Redis
- [ ] 다중 인스턴스 스케줄러: ShedLock
- [ ] 조회 성능: N+1 해결, 인덱스, k6 부하 테스트 전후 비교
- [ ] 실시간 상태: 폴링 → SSE 전환
- [ ] 스키마 관리: `ddl-auto` → Flyway 전환

### Phase 7. 배포
- [ ] Docker 이미지 빌드, GitHub Actions CI (테스트 자동 실행)
- [ ] AWS EC2 + RDS 배포, CD