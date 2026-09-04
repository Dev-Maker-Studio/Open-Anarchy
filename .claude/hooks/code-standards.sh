#!/usr/bin/env bash
# PreToolUse hook (Edit|Write|MultiEdit|NotebookEdit): injects the project code standards into
# Claude's context before every file edit, for every developer who clones the repo.
set -euo pipefail
cat >/dev/null
skill="$(cd "$(dirname "$0")/.." && pwd)/skills/darkan-code-standards/SKILL.md"
[ -f "$skill" ] || exit 0
body="$(awk 'BEGIN{fm=0} /^---$/{fm++; next} fm>=2{print}' "$skill")"
jq -n --arg ctx "$body" '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $ctx}}'
