---
name: new-endpoint
description: 새 REST API 엔드포인트나 도메인 기능(controller/service/repository/dto)을 만들 때 사용. 계층 구조, record DTO, Bean Validation, ErrorCode 추가, 커서 페이지네이션 템플릿.
---

# 새 엔드포인트 / 도메인 기능 만들기

## 0. 시작 전 확인

1. `README.md` 의 API 명세와 ERD에서 이 엔드포인트의 경로, 요청/응답 필드, 에러를 확인합니다.
2. 명세와 다르게 만들어야 할 것 같으면 **구현 전에** 사용자에게 알리고, README도 함께 고칩니다.
3. 다른 도메인의 데이터가 필요하면 그 도메인의 **service** 를 주입합니다. repository를 직접 주입하지 않습니다.

## 1. 파일 배치

```
com.ontime.{domain}
├── controller/{Name}Controller.java
├── service/{Name}Service.java
├── repository/{Name}Repository.java
├── domain/{Entity}.java
└── dto/{Action}{Name}Request.java, {Name}Response.java
```

## 2. 컨트롤러

- 경로는 `/api/v1/...`, 복수형 명사, kebab-case (`/api/v1/meeting-groups/{groupId}/invite-codes`)
- 로그인 사용자는 `@LoginUser Long userId` 로 받습니다.
- 트랜잭션을 걸지 않고, 엔티티를 반환하지 않습니다.

```java
@RestController
@RequestMapping("/api/v1/meeting-groups")
@RequiredArgsConstructor
public class MeetingGroupController {

  private final MeetingGroupService meetingGroupService;

  @PostMapping
  public ResponseEntity<MeetingGroupResponse> create(
      @LoginUser Long userId, @Valid @RequestBody CreateMeetingGroupRequest request) {
    MeetingGroupResponse response = meetingGroupService.create(userId, request);
    return ResponseEntity.created(URI.create("/api/v1/meeting-groups/" + response.id()))
        .body(response);
  }

  @GetMapping
  public CursorResponse<MeetingGroupSummary> list(
      @LoginUser Long userId,
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
    return meetingGroupService.list(userId, cursor, size);
  }
}
```

## 3. DTO (record + Bean Validation)

```java
public record CreateMeetingGroupRequest(
    @NotBlank @Size(max = 30) String name,
    @NotNull @Positive Integer graceMinutes,
    @NotNull @PositiveOrZero Long lateFineAmount) {}

public record MeetingGroupResponse(Long id, String name, Instant createdAt) {
  public static MeetingGroupResponse from(MeetingGroup group) {
    return new MeetingGroupResponse(group.getId(), group.getName(), group.getCreatedAt());
  }
}
```

- 금액은 `Long`/`long` (원 단위). `Double` 금지.
- 시각은 `Instant` 로 내보냅니다. 표시는 프론트가 Asia/Seoul로 합니다.

## 4. 서비스

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingGroupService {

  private final MeetingGroupRepository meetingGroupRepository;
  private final Clock clock;

  @Transactional
  public MeetingGroupResponse update(Long userId, Long groupId, UpdateMeetingGroupRequest request) {
    MeetingGroup group = getGroup(groupId);
    group.validateOwner(userId);            // 권한 검사는 엔티티 메서드로
    group.changeName(request.name());       // @Setter 대신 의미 있는 이름
    return MeetingGroupResponse.from(group);
  }

  private MeetingGroup getGroup(Long groupId) {
    return meetingGroupRepository.findById(groupId)
        .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
  }
}
```

- 현재 시각이 필요하면 `Instant.now(clock)`.
- 목록 조회에서 연관 엔티티를 쓰면 fetch join, `@BatchSize`, DTO 조회 중 하나로 N+1을 막습니다.

## 5. ErrorCode 추가

```java
GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "모임을 찾을 수 없어요."),
NOT_GROUP_OWNER(HttpStatus.FORBIDDEN, "방장만 할 수 있어요."),
INVITE_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 초대 코드예요."),
```

- 이름은 `대상_상황` 대문자 스네이크.
- 메시지는 사용자에게 그대로 보여줄 **해요체** 한국어 문장.
- 이미 비슷한 코드가 있으면 재사용합니다.

## 6. 커서 페이지네이션

`size + 1` 개를 조회해서 다음 페이지가 있는지 판단합니다. 커서는 마지막 항목의 id입니다 (id 내림차순).

```java
public record CursorResponse<T>(List<T> items, Long nextCursor, boolean hasNext) {

  public static <T> CursorResponse<T> of(List<T> fetched, int size, Function<T, Long> idOf) {
    boolean hasNext = fetched.size() > size;
    List<T> items = hasNext ? fetched.subList(0, size) : fetched;
    Long nextCursor = hasNext ? idOf.apply(items.get(items.size() - 1)) : null;
    return new CursorResponse<>(items, nextCursor, hasNext);
  }
}
```

```java
@Query("""
    select new com.ontime.group.dto.MeetingGroupSummary(g.id, g.name, g.createdAt)
    from GroupMember m join m.group g
    where m.user.id = :userId
      and (:cursor is null or g.id < :cursor)
    order by g.id desc
    """)
List<MeetingGroupSummary> findPageByUserId(Long userId, Long cursor, Limit limit);
```

호출 시 `Limit.of(size + 1)` 을 넘깁니다.

## 7. 테스트

`write-tests` 스킬을 따릅니다. 최소한 다음은 포함합니다.

- 정상 흐름 1개 이상
- 권한 분기: 방장 / 일반 멤버 / 비멤버
- 각 `ErrorCode` 거절 + 거절 뒤 데이터 불변
- 목록 조회: 마지막 페이지(`hasNext = false`), `size` 경계, 커서 이후 항목만 오는지 (통합 테스트)
