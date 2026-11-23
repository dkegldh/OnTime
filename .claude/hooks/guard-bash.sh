#!/usr/bin/env bash
# PreToolUse(Bash): 위험한 git/gh 명령을 실행 전에 막는다.
#   - force push, main/develop 으로 push
#   - main/develop 에서 직접 커밋
#   - .env 를 git에 추가/커밋
#   - gh pr merge
#   - gh pr create: --base develop 강제 + 전체 테스트 통과해야 허용
# exit 2 = 차단, stderr 내용이 Claude에게 전달된다.
#
# 검사는 원본 명령이 아니라 sanitize 한 문자열($scan)로 한다.
#   - heredoc 본문은 지운다.
#   - 공백이 있는 따옴표 문자열(커밋 메시지, echo 문장 등)은 __STR__ 로 바꾼다.
#     공백이 없는 따옴표 문자열("develop", '.env')은 따옴표만 벗기고 인자로 본다.
#   - 줄바꿈은 명령 구분자(;)로 바꾼다.
#   - git/gh 는 명령 시작 위치(맨 앞, ; & | ( ` { 뒤)에 있을 때만 명령으로 인정한다.
# 한계: 따옴표·heredoc 안에서 실제로 실행되는 명령은 검사하지 못한다.
#   예) bash -c '...', sh -c '...', eval "...", "$(git push -f)", bash <<EOF ... EOF
#   또한 git -C dir push, command git push, sudo git push 처럼 앞에 다른 것이 붙은 형태도 잡지 못한다.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[guard-bash] jq가 설치되어 있지 않아 검사를 건너뜁니다. (brew install jq / apt install jq)" >&2
  exit 1 # 차단하지 않는 경고
fi

cmd=$(jq -r '.tool_input.command // empty')
[[ -z "$cmd" ]] && exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

block() {
  echo "[guard-bash] $1" >&2
  exit 2
}

# 따옴표 문자열과 heredoc 본문을 걷어내고, 줄바꿈을 ; 로 바꾼 한 줄을 출력한다.
sanitize() {
  awk '
    function emit_str() {
      out = out ((buf == "" || buf ~ /[[:space:]]/) ? "__STR__" : buf)
      buf = ""
    }
    {
      line = $0
      # heredoc 본문: 종료 단어 줄까지 버린다
      if (hd != "") {
        t = line
        if (hd_strip) sub(/^\t+/, "", t)
        if (t == hd) hd = ""
        next
      }
      n = length(line)
      for (i = 1; i <= n; i++) {
        c = substr(line, i, 1)
        if (q == "\047") {                       # 작은따옴표 안
          if (c == "\047") { emit_str(); q = "" } else buf = buf c
          continue
        }
        if (q == "\"") {                         # 큰따옴표 안
          if (c == "\\" && i < n) { buf = buf substr(line, ++i, 1); continue }
          if (c == "\"") { emit_str(); q = "" } else buf = buf c
          continue
        }
        if (c == "\\" && i < n) { out = out c substr(line, ++i, 1); continue }
        if (c == "\047" || c == "\"") { q = c; buf = ""; continue }
        # heredoc 시작: <<WORD, <<-WORD, <<"WORD", <<\047WORD\047 (<<< 는 제외)
        if (c == "<" && substr(line, i, 2) == "<<" && substr(line, i + 2, 1) != "<") {
          j = i + 2
          strip = 0
          if (substr(line, j, 1) == "-") { strip = 1; j++ }
          while (substr(line, j, 1) ~ /[ \t]/) j++
          qc = substr(line, j, 1)
          if (qc == "\047" || qc == "\"") j++; else qc = ""
          w = ""
          while (j <= n && substr(line, j, 1) ~ /[A-Za-z0-9_]/) { w = w substr(line, j, 1); j++ }
          if (qc != "" && substr(line, j, 1) == qc) j++
          if (w != "") {
            pending = w; pending_strip = strip
            out = out "<<" w
            i = j - 1
            continue
          }
        }
        out = out c
      }
      if (q != "") { buf = buf " "; next }      # 따옴표가 다음 줄로 이어짐
      out = out ";"
      if (pending != "") { hd = pending; hd_strip = pending_strip; pending = "" }
    }
    END {
      if (q != "") emit_str()
      print out
    }
  ' <<<"$1"
}
scan=$(sanitize "$cmd")

current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
is_protected() { [[ "$1" == "main" || "$1" == "develop" ]]; }
# 명령 시작 위치: 맨 앞, 또는 ; & | ( ` { 뒤 (공백 허용)
cmd_start='(^|[;&|(`{])[[:space:]]*'
has_git() { grep -Eq "${cmd_start}git[[:space:]]+$1([[:space:]]|;|$)" <<<"$scan"; }
has_gh_pr() { grep -Eq "${cmd_start}gh[[:space:]]+pr[[:space:]]+$1([[:space:]]|;|$)" <<<"$scan"; }

# ---------- gh pr merge ----------
if has_gh_pr merge; then
  block "PR 머지는 하지 않습니다. 리뷰와 머지는 사람이 합니다."
fi

# ---------- git push ----------
if has_git push; then
  # 마지막 'git push' 뒤부터 다음 명령 구분자 전까지를 push 인자로 본다
  push_args=$(sed -E 's/.*git[[:space:]]+push//; s/(&&|\|\||;|\||\)|`).*//' <<<"$scan")

  if grep -Eq '(^|[[:space:]])(--force(-with-lease)?(=[^[:space:]]*)?|-[a-zA-Z]*f[a-zA-Z]*)([[:space:]]|$)|(^|[[:space:]])\+[^[:space:]]' <<<"$push_args"; then
    block "force push는 하지 않습니다. 히스토리를 고쳐야 하면 새 커밋으로 해결하세요."
  fi

  if grep -Eq '(^|[[:space:]:/])(main|develop)([[:space:]]|$)' <<<"$push_args"; then
    block "main/develop 으로 직접 push하지 않습니다. 작업 브랜치를 push하고 PR을 만드세요."
  fi

  # 'git push', 'git push origin', 'git push -u origin' 처럼 브랜치를 안 적으면 현재 브랜치가 push된다
  nonflag=0
  for a in $push_args; do [[ "$a" != -* ]] && nonflag=$((nonflag + 1)); done
  if ((nonflag <= 1)) && is_protected "$current_branch"; then
    block "현재 브랜치가 $current_branch 입니다. main/develop 에서는 push하지 않습니다."
  fi
fi

# ---------- git commit on main/develop ----------
if has_git commit && is_protected "$current_branch"; then
  if ! grep -Eq 'git[[:space:]]+(checkout[[:space:]]+-b|switch[[:space:]]+-c)' <<<"$scan"; then
    block "현재 브랜치가 $current_branch 입니다. 직접 커밋하지 말고 {type}/{이슈번호}-{요약} 브랜치를 만드세요."
  fi
fi

# ---------- .env ----------
env_re='(^|/)\.env(\.[a-zA-Z]+)?$'
env_ok_re='(^|/)\.env\.(example|sample|template)$'
if has_git add; then
  add_args=$(sed -E 's/.*git[[:space:]]+add//; s/(&&|\|\||;|\||\)|`).*//' <<<"$scan")
  for a in $add_args; do
    if grep -Eq "$env_re" <<<"$a" && ! grep -Eq "$env_ok_re" <<<"$a"; then
      block ".env 파일은 커밋하지 않습니다. ($a)"
    fi
  done
  if grep -Eq '(^|[[:space:]])(-A|--all|\.|-u)([[:space:]]|$)' <<<"$add_args"; then
    leaked=$(git ls-files --others --modified --exclude-standard 2>/dev/null | grep -E "$env_re" | grep -Ev "$env_ok_re" || true)
    [[ -n "$leaked" ]] && block ".env 파일이 .gitignore 에 없어 함께 추가됩니다: $leaked — .gitignore 에 추가하거나 파일을 지정해서 add 하세요."
  fi
fi
if has_git commit; then
  staged=$(git diff --cached --name-only 2>/dev/null | grep -E "$env_re" | grep -Ev "$env_ok_re" || true)
  [[ -n "$staged" ]] && block ".env 파일이 staged 되어 있습니다: $staged — git restore --staged 로 빼세요."
fi

# ---------- gh pr create: base 확인 + 전체 테스트 ----------
if has_gh_pr create; then
  if ! grep -Eq '(--base|-B)[[:space:]=]+develop([[:space:]]|;|$)' <<<"$scan"; then
    block "PR은 develop 으로 만듭니다. gh pr create --base develop ..."
  fi
  if [[ -x ./gradlew ]]; then
    log=$(mktemp)
    if ! ./gradlew test --console=plain >"$log" 2>&1; then
      {
        echo "[guard-bash] 전체 테스트가 실패해서 PR을 만들지 않습니다. 원인을 먼저 사용자에게 알려주세요."
        grep -E 'FAILED|error:|Exception|expected' "$log" | head -40
        echo "(전체 로그: $log)"
      } >&2
      exit 2
    fi
    rm -f "$log"
  fi
fi

exit 0
