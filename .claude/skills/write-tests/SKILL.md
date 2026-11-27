---
name: write-tests
description: 서비스/도메인 코드를 작성·수정했거나 테스트를 새로 쓰거나 고칠 때 사용. 의미 있는 테스트의 기준, 네이밍, Clock 고정, Testcontainers 통합 테스트, 동시성 테스트 작성법과 예시.
---

# 의미 있는 테스트 작성

서비스 코드를 작성하거나 수정할 때는 테스트를 같이 씁니다. 테스트 없이 기능 작업을 끝내지 않습니다.

## 의미 있는 테스트란

**그 동작이 깨지면 실패하는 테스트**입니다.
테스트를 쓰기 전에 "이 메서드의 규칙이 틀리게 바뀌면 이 테스트가 잡아내는가?"를 확인합니다.
예를 들어 `isAfter` 를 `!isBefore` 로 바꾸거나, `<=` 를 `<` 로 바꾸거나, 방장 검사를 지워도 통과하는 테스트는 의미가 없습니다.

서비스 메서드마다 아래 네 가지를 확인합니다.

1. **정상 흐름**: 기대한 상태 변경과 반환값이 맞는지
2. **규칙과 분기**: 권한(방장/멤버), 상태 전이, 금액 계산처럼 코드에 있는 `if` 하나하나
3. **경계값**: 시간과 금액이 걸린 규칙은 기준점과 그 전후
   - 유예시간: 정각 / 1초 전 / 1초 후
   - 버튼 활성: 약속 3시간 전 정각 / 1초 전
   - 빠지기: 약속 24시간 전 정각 / 1초 후
   - 반경: `arrival_radius_m` 안 / 밖 (예: 199m, 201m)
   - 상태 전이, 지각 판정, 잔액 계산은 **반드시** 포함합니다.
4. **실패 흐름**: 잘못된 요청이 정확한 `ErrorCode` 로 거절되는지, **그리고 거절된 뒤 데이터가 바뀌지 않았는지**

## 의미 없는 테스트 (쓰지 않음)

- getter/setter, 생성자, DTO 변환만 확인하는 테스트
- mock이 호출됐는지(`verify`)만 확인하고 결과나 상태는 보지 않는 테스트
- 구현 코드를 그대로 따라 써서 기대값을 계산하는 테스트 → 기대값은 **손으로 계산한 상수**로 씁니다
- 예외가 "발생하는지"만 보고 어떤 `ErrorCode` 인지 확인하지 않는 테스트

## 작성 방식

- 테스트 하나는 동작 하나만 검증합니다.
- `// given`, `// when`, `// then` 주석으로 구분합니다.
- 메서드 이름은 영어 `대상_조건_결과`, `@DisplayName` 에 한국어 설명을 붙입니다.

```java
@Test
@DisplayName("유예시간 정각에 도착하면 LATE_ARRIVED가 된다")
void arrive_atGraceDeadline_becomesLateArrived() { ... }
```

- 시간은 `Clock.fixed(...)` 로 고정합니다. 테스트에서도 `Instant.now()` 를 쓰지 않습니다.
- 예외 검증은 `ErrorCode` 까지 봅니다.

```java
assertThatThrownBy(() -> attendance.arrive(now, lat, lng))
    .isInstanceOf(BusinessException.class)
    .extracting("errorCode")
    .isEqualTo(ErrorCode.ARRIVE_TOO_FAR);
```

## 어떤 종류의 테스트를 쓸지

| 대상 | 종류 | 예시 파일 |
| --- | --- | --- |
| 엔티티의 상태 전이, 계산 로직 | 단위 테스트 (Spring 없이) | `examples/AttendanceTest.java` |
| 서비스의 분기, 권한, 예외 | 단위 테스트 (Mockito) | `examples/AttendanceServiceTest.java` |
| DB 제약, 쿼리, 부분 인덱스, 배열 타입 | 통합 테스트 (`@SpringBootTest` + Testcontainers) | `examples/IntegrationTestSupport.java` |
| 낙관적 락, 중복 부과 방지 | 동시성 테스트 | `examples/FineChargeConcurrencyTest.java` |

- 통합 테스트는 **H2를 쓰지 않습니다.** 부분 인덱스와 배열 타입이 PostgreSQL 전용입니다.
- 통합 테스트는 `IntegrationTestSupport` 를 상속해서 컨테이너를 재사용합니다. (테스트마다 컨테이너를 새로 띄우지 않음)
- 동시성 테스트는 `ExecutorService` + `CountDownLatch` 로 같은 요청을 동시에 보냅니다. 가상 스레드는 쓰지 않습니다 (Java 17).
- 동시성 테스트에서 `@Transactional` 을 테스트 클래스에 붙이지 않습니다. 스레드마다 별도 트랜잭션이어야 경합이 재현됩니다. 대신 `@AfterEach` 에서 데이터를 정리합니다.

예시 파일의 클래스·메서드 이름은 설명용입니다. 실제 코드의 이름에 맞춰 바꿉니다.

## 마무리

작업을 마칠 때 추가한 테스트와 각각이 검증하는 규칙을 짧게 요약합니다.

```
- AttendanceTest.arrive_atGraceDeadline_becomesLateArrived: 유예시간 정각 도착은 지각 도착 (경계값: 정각부터 지각)
- AttendanceTest.arrive_oneSecondBeforeGraceDeadline_becomesArrived: 유예 1초 전 도착은 정상 도착
- ...
```
