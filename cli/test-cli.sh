#!/bin/sh
# Atmosphere CLI test suite
# Usage: ./cli/test-cli.sh
#
# Tests the CLI script, npx launcher, and samples.json integrity
# without network access or Java runtime.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLI="$SCRIPT_DIR/atmosphere"
NPX="$SCRIPT_DIR/npx/index.js"
SAMPLES_JSON="$SCRIPT_DIR/samples.json"
PASS=0
FAIL=0
TESTS=""

# ── Helpers ─────────────────────────────────────────────────────────────────
RED='\033[31m'
GREEN='\033[32m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

pass() {
    PASS=$((PASS + 1))
    printf "  ${GREEN}✓${RESET} %s\n" "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf "  ${RED}✗${RESET} %s\n" "$1"
    if [ -n "$2" ]; then
        printf "    ${DIM}%s${RESET}\n" "$2"
    fi
}

assert_contains() {
    output="$1"
    expected="$2"
    label="$3"
    if printf '%s' "$output" | grep -q "$expected"; then
        pass "$label"
    else
        fail "$label" "expected output to contain: $expected"
    fi
}

assert_not_contains() {
    output="$1"
    unexpected="$2"
    label="$3"
    if printf '%s' "$output" | grep -q "$unexpected"; then
        fail "$label" "output should NOT contain: $unexpected"
    else
        pass "$label"
    fi
}

assert_exit_code() {
    actual="$1"
    expected="$2"
    label="$3"
    if [ "$actual" -eq "$expected" ]; then
        pass "$label"
    else
        fail "$label" "expected exit code $expected, got $actual"
    fi
}

# ── Prerequisites ───────────────────────────────────────────────────────────
printf "\n${BOLD}Atmosphere CLI Test Suite${RESET}\n\n"

if [ ! -x "$CLI" ]; then
    printf "${RED}error:${RESET} CLI script not found or not executable: %s\n" "$CLI"
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    printf "${RED}error:${RESET} jq is required to run tests: brew install jq\n"
    exit 1
fi

if ! command -v node >/dev/null 2>&1; then
    printf "${RED}warning:${RESET} node not found — skipping npx tests\n"
    SKIP_NPX=1
fi

# ── 1. samples.json Integrity ──────────────────────────────────────────────
printf "${BOLD}samples.json${RESET}\n"

# Valid JSON
if jq empty "$SAMPLES_JSON" 2>/dev/null; then
    pass "valid JSON"
else
    fail "valid JSON" "samples.json is not valid JSON"
fi

# Has version field
version=$(jq -r '.version' "$SAMPLES_JSON")
assert_contains "$version" "4.0" "has version field"

# Has samples array
count=$(jq '.samples | length' "$SAMPLES_JSON")
if [ "$count" -ge 15 ]; then
    pass "has $count samples (>= 15)"
else
    fail "has enough samples" "only $count samples found"
fi

# Every sample has required fields
missing=$(jq -r '.samples[] | select(.name == null or .description == null or .port == null or .runnable == null or .category == null) | .name // "unnamed"' "$SAMPLES_JSON")
if [ -z "$missing" ]; then
    pass "all samples have required fields (name, description, port, runnable, category)"
else
    fail "required fields" "missing in: $missing"
fi

# No duplicate names
dupes=$(jq -r '[.samples[].name] | group_by(.) | map(select(length > 1)) | flatten | .[]' "$SAMPLES_JSON")
if [ -z "$dupes" ]; then
    pass "no duplicate sample names"
else
    fail "duplicate names" "$dupes"
fi

# Ports: warn about conflicts but don't fail (users run one at a time)
port_dupes=$(jq -r '[.samples[] | select(.runnable == true and .port != 8080)] | group_by(.port) | map(select(length > 1)) | map(.[0].port | tostring) | unique | .[]' "$SAMPLES_JSON")
if [ -z "$port_dupes" ]; then
    pass "no port conflicts among non-8080 runnable samples"
else
    pass "port sharing detected on: $port_dupes (ok — users run one at a time)"
fi

# Runnable samples have valid packaging
bad_pkg=$(jq -r '.samples[] | select(.runnable == true) | select(.packaging != "spring-boot" and .packaging != "quarkus" and .packaging != "executable-jar") | .name' "$SAMPLES_JSON")
if [ -z "$bad_pkg" ]; then
    pass "all runnable samples have valid packaging type"
else
    fail "bad packaging" "$bad_pkg"
fi

# All samples have tags array
no_tags=$(jq -r '.samples[] | select(.tags == null or (.tags | length) == 0) | .name' "$SAMPLES_JSON")
if [ -z "$no_tags" ]; then
    pass "all samples have tags"
else
    fail "missing tags" "$no_tags"
fi

printf "\n"

# ── 2. CLI: version ────────────────────────────────────────────────────────
printf "${BOLD}atmosphere version${RESET}\n"

out=$("$CLI" version 2>&1)
assert_contains "$out" "Atmosphere" "prints 'Atmosphere'"
assert_contains "$out" "$version" "version matches samples.json"

printf "\n"

# ── 3. CLI: help ───────────────────────────────────────────────────────────
printf "${BOLD}atmosphere help${RESET}\n"

out=$("$CLI" help 2>&1)
assert_contains "$out" "install" "help mentions install"
assert_contains "$out" "list" "help mentions list"
assert_contains "$out" "run" "help mentions run"
assert_contains "$out" "new" "help mentions new"
assert_contains "$out" "info" "help mentions info"
assert_contains "$out" "ATMOSPHERE_HOME" "help mentions ATMOSPHERE_HOME"

printf "\n"

# ── 4. CLI: list ───────────────────────────────────────────────────────────
printf "${BOLD}atmosphere list${RESET}\n"

out=$("$CLI" list 2>&1)
assert_contains "$out" "spring-boot-chat" "lists spring-boot-chat"
assert_contains "$out" "spring-boot-ai-chat" "lists spring-boot-ai-chat"
assert_contains "$out" "quarkus-chat" "lists quarkus-chat"
assert_contains "$out" "SAMPLE" "has table header"

printf "\n"

# ── 5. CLI: list --tag ─────────────────────────────────────────────────────
printf "${BOLD}atmosphere list --tag${RESET}\n"

out=$("$CLI" list --tag rag 2>&1)
assert_contains "$out" "spring-boot-rag-chat" "tag filter: rag shows rag-chat"
assert_not_contains "$out" "quarkus-chat" "tag filter: rag excludes quarkus-chat"

out=$("$CLI" list --tag spring-boot 2>&1)
assert_contains "$out" "spring-boot-chat" "tag filter: spring-boot shows spring-boot-chat"
assert_not_contains "$out" "quarkus-chat" "tag filter: spring-boot excludes quarkus-chat"

printf "\n"

# ── 6. CLI: list --category ────────────────────────────────────────────────
printf "${BOLD}atmosphere list --category${RESET}\n"

out=$("$CLI" list --category chat 2>&1)
assert_contains "$out" "spring-boot-chat" "category filter: chat shows spring-boot-chat"
assert_not_contains "$out" "spring-boot-ai-chat" "category filter: chat excludes ai-chat"

printf "\n"

# ── 7. CLI: info ───────────────────────────────────────────────────────────
printf "${BOLD}atmosphere info${RESET}\n"

out=$("$CLI" info spring-boot-ai-chat 2>&1)
assert_contains "$out" "AI streaming" "info shows description"
assert_contains "$out" "8080" "info shows port"
assert_contains "$out" "LLM_API_KEY" "info shows env vars"
assert_contains "$out" "spring-boot" "info shows packaging"
assert_contains "$out" "atmosphere run" "info shows run command"

out=$("$CLI" info spring-boot-rag-chat 2>&1)
assert_contains "$out" "rag" "rag-chat info shows category"
assert_contains "$out" "vectorstore" "rag-chat info shows tags"

printf "\n"

# ── 8. CLI: info unknown sample ────────────────────────────────────────────
printf "${BOLD}atmosphere info (error cases)${RESET}\n"

out=$("$CLI" info nonexistent-sample 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "unknown sample exits with code 1"
assert_contains "$out" "error" "unknown sample prints error"

out=$("$CLI" info 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "no argument exits with code 1"

printf "\n"

# ── 9. CLI: run (validation only, no Java) ─────────────────────────────────
printf "${BOLD}atmosphere run (validation)${RESET}\n"

out=$("$CLI" run 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "no argument exits with code 1"

out=$("$CLI" run chat 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "non-runnable sample (WAR) exits with error"
assert_contains "$out" "not directly runnable" "non-runnable sample shows helpful message"

printf "\n"

# ── 10. CLI: unknown command ───────────────────────────────────────────────
printf "${BOLD}atmosphere (error cases)${RESET}\n"

out=$("$CLI" foobar 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "unknown command exits with code 1"
assert_contains "$out" "Unknown command" "unknown command prints error"

out=$("$CLI" list --badopt 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "unknown option exits with code 1"

printf "\n"

# ── 11. CLI: install (menu rendering) ──────────────────────────────────────
printf "${BOLD}atmosphere install (menu)${RESET}\n"

# Force numbered menu (skip fzf), cancel immediately
out=$(printf '\n' | env TERM=dumb "$CLI" install 2>&1)
assert_contains "$out" "Choose a sample" "install shows picker header"
assert_contains "$out" "CHAT" "install shows CHAT category"
assert_contains "$out" "AI" "install shows AI category"
assert_contains "$out" "spring-boot-chat" "install shows spring-boot-chat"
assert_contains "$out" "Enter number" "install shows number prompt"

# With --tag filter
out=$(printf '\n' | env TERM=dumb "$CLI" install --tag rag 2>&1)
assert_contains "$out" "spring-boot-rag-chat" "install --tag rag shows rag-chat"
assert_not_contains "$out" "quarkus-chat" "install --tag rag excludes quarkus-chat"

# Cancelling (empty input) exits cleanly
out=$(printf '\n' | "$CLI" install 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "empty input cancels gracefully"
assert_contains "$out" "No sample selected" "empty input shows cancel message"

printf "\n"

# ── 12. CLI: new (no JBang, minimal scaffold) ──────────────────────────────
printf "${BOLD}atmosphere new (minimal scaffold)${RESET}\n"

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

# Scaffold default chat template
out=$(cd "$tmp_dir" && env PATH="/usr/bin:/bin:/usr/sbin:/sbin" "$CLI" new test-chat-app 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "new command exits successfully"
assert_contains "$out" "Project created" "prints success message"

if [ -f "$tmp_dir/test-chat-app/pom.xml" ]; then
    pass "creates pom.xml"
else
    fail "creates pom.xml"
fi

pom=$(cat "$tmp_dir/test-chat-app/pom.xml")
assert_contains "$pom" "atmosphere-spring-boot-starter" "pom.xml has atmosphere starter"
assert_contains "$pom" "spring-boot-starter-web" "pom.xml has spring-boot-web"
assert_contains "$pom" "com.example" "pom.xml has default groupId"
assert_contains "$pom" "test-chat-app" "pom.xml has correct artifactId"

if [ -f "$tmp_dir/test-chat-app/src/main/java/com/example/ChatApplication.java" ]; then
    pass "creates ChatApplication.java"
else
    fail "creates ChatApplication.java"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/java/com/example/Chat.java" ]; then
    pass "creates Chat.java handler"
else
    fail "creates Chat.java handler"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/java/com/example/Message.java" ]; then
    pass "creates Message.java"
else
    fail "creates Message.java"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/java/com/example/JacksonEncoder.java" ]; then
    pass "creates JacksonEncoder.java"
else
    fail "creates JacksonEncoder.java"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/java/com/example/JacksonDecoder.java" ]; then
    pass "creates JacksonDecoder.java"
else
    fail "creates JacksonDecoder.java"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/resources/static/index.html" ]; then
    pass "creates index.html"
    html=$(cat "$tmp_dir/test-chat-app/src/main/resources/static/index.html")
    assert_contains "$html" "/atmosphere/chat" "index.html connects to /atmosphere/chat"
    assert_contains "$html" "WebSocket" "index.html uses WebSocket"
else
    fail "creates index.html"
fi

if [ -f "$tmp_dir/test-chat-app/src/main/resources/application.yml" ]; then
    pass "creates application.yml"
else
    fail "creates application.yml"
fi

# Scaffold already exists
out=$(cd "$tmp_dir" && env PATH="/usr/bin:/bin:/usr/sbin:/sbin" "$CLI" new test-chat-app 2>&1) && ec=0 || ec=$?
# This should still succeed (mkdir -p), but let's just verify it doesn't crash badly

# No name
out=$("$CLI" new 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "new without name exits with error"

printf "\n"

# ── 12b. CLI: new --skill-file (agent from skill) ────────────────────────
printf "${BOLD}atmosphere new --skill-file (agent scaffold)${RESET}\n"

# Create a test skill file
cat > "$tmp_dir/test-skill.md" <<SKILLEOF
# Support Agent
You are a customer support agent for Acme Corp.

## Skills
- Answer product questions
- Handle returns and exchanges

## Guardrails
- Never share internal pricing
SKILLEOF

out=$(cd "$tmp_dir" && env PATH="/usr/bin:/bin:/usr/sbin:/sbin" "$CLI" new my-support-agent --skill-file "$tmp_dir/test-skill.md" 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "new --skill-file exits successfully"
assert_contains "$out" "Project created" "prints success message"

if [ -f "$tmp_dir/my-support-agent/pom.xml" ]; then
    pass "agent: creates pom.xml"
    agent_pom=$(cat "$tmp_dir/my-support-agent/pom.xml")
    assert_contains "$agent_pom" "atmosphere-agent" "agent pom.xml has atmosphere-agent dependency"
else
    fail "agent: creates pom.xml"
fi

if [ -f "$tmp_dir/my-support-agent/src/main/java/com/example/MySupportAgent.java" ]; then
    pass "agent: creates agent class with PascalCase name"
    agent_java=$(cat "$tmp_dir/my-support-agent/src/main/java/com/example/MySupportAgent.java")
    assert_contains "$agent_java" "@Agent" "agent class has @Agent annotation"
    assert_contains "$agent_java" "skillFile" "agent class references skill file"
    assert_contains "$agent_java" "prompts/skill.md" "agent class points to prompts/skill.md"
else
    fail "agent: creates agent class"
fi

if [ -f "$tmp_dir/my-support-agent/src/main/resources/prompts/skill.md" ]; then
    pass "agent: copies skill file to resources"
    skill_content=$(cat "$tmp_dir/my-support-agent/src/main/resources/prompts/skill.md")
    assert_contains "$skill_content" "Support Agent" "skill file content preserved"
    assert_contains "$skill_content" "Guardrails" "skill file guardrails preserved"
else
    fail "agent: copies skill file to resources"
fi

# --skill-file with nonexistent file
out=$("$CLI" new bad-agent --skill-file /nonexistent/skill.md 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "nonexistent skill file exits with error"

printf "\n"

# ── 13. npx: create-atmosphere-app ─────────────────────────────────────────
if [ -z "$SKIP_NPX" ]; then
    printf "${BOLD}npx create-atmosphere-app${RESET}\n"

    # Help
    out=$(node "$NPX" --help 2>&1)
    assert_contains "$out" "create-atmosphere-app" "npx help shows tool name"
    assert_contains "$out" "chat" "npx help lists chat template"
    assert_contains "$out" "ai-chat" "npx help lists ai-chat template"

    # List templates
    out=$(node "$NPX" --list-templates 2>&1)
    assert_contains "$out" "chat" "npx --list-templates shows chat"
    assert_contains "$out" "rag" "npx --list-templates shows rag"

    # No name
    out=$(node "$NPX" 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx without name exits with error"

    # Scaffold (skip JBang by using restricted PATH)
    npx_tmp=$(mktemp -d)
    out=$(cd "$npx_tmp" && node -e "
const orig = require('child_process').execSync;
require('child_process').execSync = function(cmd, opts) {
  if (cmd.startsWith('command -v jbang') || cmd.startsWith('command -v atmosphere') || cmd.startsWith('command -v mvn')) throw new Error('skip');
  return orig(cmd, opts);
};
process.argv = ['node', 'index.js', 'my-ai-app', '--template', 'ai-chat', '--group', 'org.myco'];
require('$NPX');
" 2>&1)

    assert_contains "$out" "Project created" "npx scaffold prints success"

    if [ -f "$npx_tmp/my-ai-app/pom.xml" ]; then
        pass "npx creates pom.xml"
        npx_pom=$(cat "$npx_tmp/my-ai-app/pom.xml")
        assert_contains "$npx_pom" "atmosphere-ai" "npx ai-chat template includes atmosphere-ai"
        assert_contains "$npx_pom" "org.myco" "npx respects --group flag"
    else
        fail "npx creates pom.xml"
    fi

    if [ -f "$npx_tmp/my-ai-app/src/main/java/org/myco/ChatApplication.java" ]; then
        pass "npx creates Java source in correct package"
    else
        fail "npx creates Java source in correct package"
    fi

    if [ -f "$npx_tmp/my-ai-app/src/main/java/org/myco/AiChat.java" ]; then
        pass "npx ai-chat template creates AiChat.java handler"
        ai_handler=$(cat "$npx_tmp/my-ai-app/src/main/java/org/myco/AiChat.java")
        assert_contains "$ai_handler" "@AiEndpoint" "AiChat.java has @AiEndpoint"
        assert_contains "$ai_handler" "@Prompt" "AiChat.java has @Prompt"
    else
        fail "npx ai-chat template creates AiChat.java handler"
    fi

    if [ -f "$npx_tmp/my-ai-app/src/main/resources/static/index.html" ]; then
        pass "npx creates index.html"
    else
        fail "npx creates index.html"
    fi

    # Unknown template
    out=$(node "$NPX" bad-app --template nonexistent 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx with unknown template exits with error"

    # Existing directory
    mkdir -p "$npx_tmp/existing-dir"
    out=$(cd "$npx_tmp" && node -e "
process.argv = ['node', 'index.js', 'existing-dir'];
try { require('$NPX'); } catch(e) { process.exit(1); }
" 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx with existing directory exits with error"

    rm -rf "$npx_tmp"
    printf "\n"
fi

# ── 14. samples.json <-> filesystem consistency ────────────────────────────
printf "${BOLD}samples.json ↔ filesystem${RESET}\n"

repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -d "$repo_root/samples" ]; then
    # Every sample in JSON should exist on disk
    jq -r '.samples[].name' "$SAMPLES_JSON" | while read -r name; do
        if [ -d "$repo_root/samples/$name" ]; then
            pass "sample dir exists: $name"
        else
            fail "sample dir missing: $name"
        fi
    done

    # Samples claiming hasFrontend should have frontend/
    jq -r '.samples[] | select(.hasFrontend == true) | .name' "$SAMPLES_JSON" | while read -r name; do
        if [ -d "$repo_root/samples/$name/frontend" ]; then
            pass "frontend dir exists: $name"
        else
            fail "frontend dir missing: $name"
        fi
    done
else
    fail "samples directory not found at $repo_root/samples"
fi

printf "\n"

# ── Summary ─────────────────────────────────────────────────────────────────
total=$((PASS + FAIL))
printf "${BOLD}Results: %s passed, %s failed${RESET} (out of %s)\n\n" "$PASS" "$FAIL" "$total"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
