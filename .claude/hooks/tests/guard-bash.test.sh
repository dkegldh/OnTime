#!/usr/bin/env bash
# guard-bash.sh 검증 스크립트
#   실행: bash .claude/hooks/tests/guard-bash.test.sh
#   임시 git 저장소(develop, feature/1-x 브랜치)를 만들고, 그 안에서 hook에 명령을 넣어 exit code를 확인한다.
#   임시 저장소에는 gradlew 가 없으므로 gh pr create 검사 때 전체 테스트는 돌지 않는다.
set -uo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq가 필요합니다." >&2; exit 1; }

HOOK="$(cd "$(dirname "$0")/.." && pwd)/guard-bash.sh"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT

git -C "$repo" init -q -b develop
git -C "$repo" -c user.name=test -c user.email=test@example.com commit -q --allow-empty -m init
git -C "$repo" branch feature/1-x

pass=0
fail=0

# check <allow|block> <브랜치> <명령>
check() {
  local expect=$1 branch=$2 cmd=$3 code actual
  git -C "$repo" checkout -q "$branch"
  jq -n --arg c "$cmd" '{tool_input: {command: $c}}' |
    CLAUDE_PROJECT_DIR="$repo" bash "$HOOK" >/dev/null 2>&1
  code=$?
  if ((code == 2)); then actual=block; else actual=allow; fi
  if [[ "$actual" == "$expect" ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL [%s 기대, %s 결과] (branch=%s)\n%s\n\n' "$expect" "$actual" "$branch" "$cmd"
  fi
}

# ===== 통과해야 함: 텍스트 안에만 금지 문구가 있는 경우 (오탐) =====
check allow feature/1-x "cat > /tmp/body.md <<'EOF'
- gh pr merge 는 hook이 차단합니다
- git push --force 금지
EOF"
check allow feature/1-x 'cat > /tmp/body.md <<EOF
git push origin develop
EOF'
check allow feature/1-x 'git commit -m "docs: git push --force 금지 규칙 추가"'
check allow develop 'echo "git push origin develop"'
check allow develop 'echo git push origin develop'
check allow feature/1-x 'gh issue create --title "t" --body "gh pr create 시 테스트 실행, gh pr merge 차단"'
check allow feature/1-x 'git log --grep "git push -f"'

# ===== 통과해야 함: 정상 명령 =====
check allow feature/1-x 'git push -u origin feature/1-x'
check allow feature/1-x 'git commit -m "fix: 오탐 수정 (#9)"'
check allow develop 'git checkout -b fix/9-x && git commit -m "fix: y"'
check allow feature/1-x 'gh pr create --base develop --title "t" --body-file /tmp/body.md'
check allow feature/1-x 'git add .env.example'

# ===== 막아야 함: 실제 실행되는 금지 명령 =====
check block feature/1-x 'gh pr merge 8'
check block feature/1-x 'cd x && gh pr merge 8'
check block feature/1-x 'git push --force'
check block feature/1-x 'git push -f origin feature/1-x'
check block feature/1-x 'git push --force-with-lease origin feature/1-x'
check block feature/1-x 'git push origin +feature/1-x'
check block feature/1-x 'git push origin develop'
check block feature/1-x 'git push origin "develop"'
check block feature/1-x 'git push origin HEAD:main'
check block develop 'git push'
check block develop 'git push -u origin'
check block develop 'git commit -m "feat: x"'
check block feature/1-x 'git add .env'
check block feature/1-x "git add '.env'"
check block feature/1-x 'gh pr create --title "t" --body "b"'
check block feature/1-x 'gh pr create --base main --title "t"'

# 명령 구분자 뒤 / 여러 줄 / 서브셸
check block feature/1-x 'echo "done" && git push --force'
check block feature/1-x 'echo hi
git push -f'
check block feature/1-x 'x=$(git push -f)'
check block feature/1-x 'echo `git push -f`'
check block feature/1-x "cat > /tmp/body.md <<'EOF'
본문
EOF
git push --force"

echo "통과 $pass, 실패 $fail"
((fail == 0))
