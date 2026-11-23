#!/usr/bin/env bash
# PreToolUse(Edit|Write): 빌드 설정 파일을 고치려 하면 사용자에게 확인을 받는다.
# (의존성은 요청받지 않으면 추가하지 않고, 이유와 함께 먼저 제안한다는 규칙)
set -uo pipefail
command -v jq >/dev/null 2>&1 || exit 0

file=$(jq -r '.tool_input.file_path // empty')
case "$(basename -- "$file")" in
  build.gradle | settings.gradle | libs.versions.toml | gradle.properties)
    reason="빌드 설정($(basename -- "$file")) 변경입니다. 요청받지 않은 의존성 추가라면 거절하고, Claude에게 추가 이유를 먼저 설명하게 하세요."
    jq -n --arg r "$reason" '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "ask",
        permissionDecisionReason: $r
      }
    }'
    ;;
esac
exit 0
