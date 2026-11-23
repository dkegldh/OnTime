#!/usr/bin/env bash
# PostToolUse(Edit|Write): 방금 수정된 파일에서 문자열로 잡을 수 있는 규칙 위반을 찾는다.
# 위반이 있으면 exit 2 → stderr 가 Claude에게 전달되어 바로 고치게 된다.
# (dirty checking 으로 원장을 바꾸는 것처럼 문자열로 못 잡는 규칙은 code-reviewer 에이전트가 본다)
set -uo pipefail
command -v jq >/dev/null 2>&1 || exit 0

file=$(jq -r '.tool_input.file_path // empty')
[[ -n "$file" && -f "$file" ]] || exit 0

errs=()

# 주석 줄을 제외하고 패턴을 찾는다. 결과: "라인번호:내용"
find_code() {
  grep -nE "$@" -- "$file" 2>/dev/null | grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' | head -5
}
add() { # $1 = 메시지, $2 = 매칭 결과
  [[ -n "$2" ]] && errs+=("- $1"$'\n'"$(sed 's/^/    L/' <<<"$2")")
}

case "$file" in
  *.java)
    add "현재 시각은 주입받은 Clock으로 구하세요: Instant.now(clock). (테스트는 Clock.fixed)" \
      "$(find_code '(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\.now\(\)|System\.currentTimeMillis\(\)')"

    if [[ "$file" == */src/main/* ]]; then
      add "@Setter 금지. 상태 변경은 의미 있는 이름의 메서드로 하세요. (예: attendance.depart(now))" \
        "$(find_code '@Setter')"
      add "금액은 long(원 단위)으로 다룹니다. double/float 금지." \
        "$(find_code -i '(double|float)[[:space:]]+[a-z_]*(amount|fine|balance|price|fee)')"
    fi

    add "@Disabled 로 테스트를 끄지 마세요. 실패 원인을 먼저 사용자에게 알려주세요." \
      "$(find_code '@Disabled')"

    add "fine_ledger 는 append-only 입니다. UPDATE/DELETE 대신 WAIVE/PAYMENT 행을 추가하세요." \
      "$(find_code -i '(update|delete[[:space:]]+from)[[:space:]]+(fine_ledger|FineLedger)([^A-Za-z_]|$)|fineLedgerRepository\.delete')"
    if [[ "$(basename -- "$file")" == FineLedgerRepository.java ]]; then
      add "FineLedgerRepository 에 @Modifying 쿼리를 두지 마세요. (원장은 append-only)" \
        "$(find_code '@Modifying')"
    fi

    add "Spring Boot 4: @MockBean/@SpyBean 대신 @MockitoBean/@MockitoSpyBean 을 쓰세요." \
      "$(find_code '@(MockBean|SpyBean)([^A-Za-z]|$)')"
    add "Jackson 3: com.fasterxml.jackson.databind 대신 tools.jackson.databind 패키지를 쓰세요." \
      "$(find_code 'import[[:space:]]+com\.fasterxml\.jackson\.(databind|core)')"
    add "RestTemplate 대신 RestClient 를 쓰세요." \
      "$(find_code 'RestTemplate')"
    ;;

  */application*.yml | */application*.yaml | */application*.properties)
    name=$(basename -- "$file")
    if [[ "$name" != *test* ]]; then
      add "비밀값을 설정 파일에 직접 적지 마세요. 환경 변수로 받으세요: \${JWT_SECRET}" \
        "$(find_code -i '(password|secret|client-secret|secret-key|api-key)[[:space:]]*[:=][[:space:]]*[^[:space:]$#]' | grep -v '\${' || true)"
    fi
    if [[ "$name" != *local* && "$name" != *test* ]]; then
      add "운영에 적용될 수 있는 설정에서 ddl-auto update/create 를 쓰지 마세요. (local/test 프로필에서만)" \
        "$(find_code -i 'ddl-auto[[:space:]]*[:=][[:space:]]*(update|create)')"
    fi
    ;;
esac

if ((${#errs[@]} > 0)); then
  {
    echo "[check-java-rules] $file 에서 CLAUDE.md 규칙 위반을 찾았습니다. 고친 뒤 계속하세요."
    printf '%s\n' "${errs[@]}"
  } >&2
  exit 2
fi
exit 0
