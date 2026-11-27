---
name: ship-issue
description: 기능/버그/리팩터링 작업을 GitHub 이슈 작성부터 브랜치, 커밋, 셀프 리뷰, 전체 테스트, PR 생성까지 진행하는 절차. 사용자가 /ship-issue 로 시작한다.
disable-model-invocation: true
---

# 이슈 → 브랜치 → 구현 → 리뷰 → 테스트 → PR

작업 내용: $ARGUMENTS

아래 단계를 순서대로 진행합니다. 단계를 건너뛰지 않습니다.

## 1. 이슈 작성

1. `.github/ISSUE_TEMPLATE/` 에서 작업 성격에 맞는 템플릿을 읽고, 그 형식대로 제목과 본문을 작성합니다.
2. `docs/ROADMAP.md` 에서 이 작업이 어느 Phase 항목인지 확인하고 본문에 적습니다.
3. **이슈를 만들기 전에 제목과 본문을 사용자에게 보여주고 확인을 받습니다.**
4. 확인을 받으면 `gh issue create` 로 생성하고, 이슈 번호를 기록합니다.

## 2. 브랜치 생성

항상 최신 `develop` 에서 만듭니다.

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

- 작업 요약은 영어 소문자 kebab-case, 3~4단어 이내.
- `main`, `develop` 에 직접 커밋하거나 push하지 않습니다. (hook이 막습니다)

## 3. 구현과 커밋

- 새 API/도메인 기능이면 `new-endpoint` 스킬, 테스트는 `write-tests` 스킬을 따릅니다.
- 커밋은 논리적인 단위로 나눕니다. 형식은 Conventional Commits + 한국어 설명 + 이슈 번호입니다.

```
feat: 도착 버튼 반경 검증 추가 (#7)
fix: 유예시간 정각 도착이 정상 도착으로 처리되던 문제 수정 (#12)
refactor: 지각 판정 로직을 Attendance 엔티티로 이동 (#15)
test: 벌금 면제 후 잔액 계산 테스트 추가 (#18)
docs: README API 명세에 excuse 엔드포인트 추가 (#20)
chore: ShedLock 의존성 추가 (#3)
```

- 이슈 범위를 벗어난 수정이 필요하면 같이 고치지 말고 먼저 알려줍니다.
- 설계(README)와 다르게 구현해야 하면 구현 전에 알려주고, README도 같은 브랜치에서 고칩니다.

## 4. 리뷰 (에이전트에게 맡김)

직접 채점하지 않고 별도 에이전트에게 맡깁니다. 두 에이전트는 서로 독립적이므로 동시에 실행합니다.

- **code-reviewer**: `git diff develop...HEAD` 를 규칙/설계 기준으로 리뷰
- **test-auditor**: 추가·변경된 테스트가 규칙을 실제로 지키는지 검사

에이전트에게 이슈 번호와 이슈의 요구사항 목록을 같이 넘깁니다.
`[심각]` 항목은 모두 고칩니다. `[권장]` 항목은 고치거나, 고치지 않는 이유를 PR 본문에 적습니다.
고친 뒤에는 바뀐 부분만 다시 리뷰를 요청합니다.

## 5. 테스트

- **test-runner** 에이전트로 `./gradlew test` 전체를 실행합니다.
- 실패하면 PR을 만들지 않고, 원인을 사용자에게 먼저 알려줍니다.
- 기존 테스트가 실패하면 테스트를 고치기 전에 "왜 실패하는지"를 먼저 알려줍니다.

## 6. PR 작성

```bash
git push -u origin {브랜치명}
gh pr create --base develop --title "..." --body-file <본문파일>
```

- 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 형식을 따르고, 다음을 포함합니다.
  - `Closes #이슈번호`
  - 셀프 리뷰 결과 (리뷰 에이전트가 지적한 것과 처리 결과)
  - 추가한 테스트와 각각이 검증하는 규칙
- `gh pr create` 직전에 hook이 전체 테스트를 한 번 더 돌립니다. 실패하면 PR이 만들어지지 않습니다.
- **PR 머지는 하지 않습니다.** force push도 하지 않습니다.

> 참고: PR이 `develop` 으로 머지되면 `Closes #번호` 로 이슈가 자동으로 닫히지 않습니다.
> `develop` → `main` 머지 때 닫히거나 직접 닫아야 합니다.

## 7. 마무리

- Phase 항목이 끝났다면 `docs/ROADMAP.md` 체크박스를 갱신하는 커밋을 같은 PR에 포함합니다.
- 사용자에게 PR 링크, 추가한 테스트 요약, 남은 `[권장]` 항목을 짧게 보고합니다.
