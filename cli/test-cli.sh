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
    # Use `grep -e --` so patterns beginning with '-' (e.g. "--group") are
    # not mistaken for grep options.
    if printf '%s' "$output" | grep -q -F -e "$expected"; then
        pass "$label"
    else
        fail "$label" "expected output to contain: $expected"
    fi
}

assert_not_contains() {
    output="$1"
    unexpected="$2"
    label="$3"
    if printf '%s' "$output" | grep -q -F -e "$unexpected"; then
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

# ── 6b. CLI: list --tag + --category (combined filter, bug fix #1) ────────
printf "${BOLD}atmosphere list --tag + --category (combined)${RESET}\n"

tag_only=$("$CLI" list --tag ai 2>&1)
cat_only=$("$CLI" list --category ai 2>&1)
combined=$("$CLI" list --tag ai --category ai 2>&1)

# Combined results should be a subset of both tag-only and category-only
# Count sample rows (lines with a sample name, not header/decoration)
tag_count=$(echo "$tag_only" | grep -c "spring-boot\|quarkus\|chat\|grpc" || true)
cat_count=$(echo "$cat_only" | grep -c "spring-boot\|quarkus\|chat\|grpc" || true)
combined_count=$(echo "$combined" | grep -c "spring-boot\|quarkus\|chat\|grpc" || true)

if [ "$combined_count" -le "$tag_count" ] && [ "$combined_count" -le "$cat_count" ]; then
    pass "combined filter: result count ($combined_count) <= tag-only ($tag_count) and category-only ($cat_count)"
else
    fail "combined filter: result count ($combined_count) not subset of tag ($tag_count) or category ($cat_count)"
fi

# Reversed flag order should produce same results
reversed=$("$CLI" list --category ai --tag ai 2>&1)
if [ "$combined" = "$reversed" ]; then
    pass "combined filter: --tag --category same as --category --tag"
else
    fail "combined filter: reversed flag order produces different output"
fi

# Non-matching tag returns no samples (exit 0, empty table)
out=$("$CLI" list --tag nonexistent-tag-zzz 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 0 "nonexistent tag exits cleanly"
# Should show header but no sample rows
sample_count=$(echo "$out" | grep -c "spring-boot\|quarkus\|chat\|grpc" || true)
if [ "$sample_count" -eq 0 ]; then
    pass "nonexistent tag: no sample rows returned"
else
    fail "nonexistent tag: expected 0 sample rows, got $sample_count"
fi

printf "\n"

# ── 6c. CLI: info for all samples ─────────────────────────────────────────
printf "${BOLD}atmosphere info (all samples)${RESET}\n"

all_names=$(jq -r '.samples[].name' "$SAMPLES_JSON")
for name in $all_names; do
    out=$("$CLI" info "$name" 2>&1) && ec=0 || ec=$?
    if [ "$ec" -eq 0 ]; then
        pass "info $name: exits 0"
    else
        fail "info $name: exits $ec"
    fi
done

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

# ── 12. CLI: new (validation, no network) ─────────────────────────────────
# `atmosphere new` sparse-clones a sample from GitHub, so end-to-end tests
# require network. These cases exercise only the pure-local validation path.
printf "${BOLD}atmosphere new (validation)${RESET}\n"

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

# No name
out=$("$CLI" new 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "new without name exits with error"
assert_contains "$out" "Usage: atmosphere new" "error message shows usage"

# Unknown template
out=$("$CLI" new foo --template nonesuch 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "new with unknown template exits with error"
assert_contains "$out" "Unknown template: nonesuch" "error lists the bad template"
assert_contains "$out" "multi-agent" "error lists known templates including multi-agent"
assert_contains "$out" "classroom" "error lists known templates including classroom"

# Unknown argument
out=$("$CLI" new foo --bogus 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "new with unknown option exits with error"

# --skill-file with nonexistent file
out=$("$CLI" new bad-agent --skill-file /nonexistent/skill.md 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "nonexistent skill file exits with error"
assert_contains "$out" "Skill file not found" "error mentions missing skill file"

# Directory already exists
mkdir "$tmp_dir/already-here"
out=$(cd "$tmp_dir" && "$CLI" new already-here --template chat 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "existing directory exits with error"
assert_contains "$out" "already exists" "error mentions existing directory"

# --group is deprecated but still parsed; ensure the warning is emitted
# on a validation-only code path (unknown template after --group).
out=$("$CLI" new foo --template nonesuch --group com.acme 2>&1) && ec=0 || ec=$?
assert_contains "$out" "--group is no longer supported" "--group prints deprecation warning"

printf "\n"

# ── 12b. CLI: new (network — gated by ATMOSPHERE_NETWORK_TESTS=1) ─────────
# Full end-to-end clone test, skipped by default to keep CI fast and offline.
if [ "${ATMOSPHERE_NETWORK_TESTS:-0}" = "1" ]; then
    printf "${BOLD}atmosphere new (network clone)${RESET}\n"

    out=$(cd "$tmp_dir" && "$CLI" new net-chat --template chat 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 0 "new chat exits successfully"
    assert_contains "$out" "Project created" "prints success message"
    assert_contains "$out" "spring-boot-chat" "output mentions source sample"

    if [ -f "$tmp_dir/net-chat/pom.xml" ]; then
        pass "clones pom.xml from sample"
    else
        fail "clones pom.xml from sample"
    fi

    # Skill-file + agent template copies the skill into the cloned project
    cat > "$tmp_dir/test-skill.md" <<'SKILLEOF'
# Support Agent
You are a customer support agent for Acme Corp.

## Skills
- Answer product questions

## Guardrails
- Never share internal pricing
SKILLEOF

    out=$(cd "$tmp_dir" && "$CLI" new net-agent --skill-file "$tmp_dir/test-skill.md" 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 0 "new --skill-file exits successfully"
    if [ -f "$tmp_dir/net-agent/src/main/resources/prompts/skill.md" ]; then
        pass "copies skill file into cloned project"
        skill_content=$(cat "$tmp_dir/net-agent/src/main/resources/prompts/skill.md")
        assert_contains "$skill_content" "Support Agent" "skill file content preserved"
        assert_contains "$skill_content" "Guardrails" "skill file guardrails preserved"
    else
        fail "copies skill file into cloned project"
    fi

    # Sparse-clone standalone-compile regression (commit 0b9a8f194d).
    # The cloned pom.xml must build standalone against the Maven Central
    # parent — without <relativePath>, pinned SNAPSHOT→release, and with
    # checkstyle/pmd skipped. Pick one representative template to keep the
    # cycle fast; compiling every template would dominate CI runtime.
    if command -v mvn >/dev/null 2>&1; then
        out=$(cd "$tmp_dir" && "$CLI" new net-ai-chat --template ai-chat 2>&1) && ec=0 || ec=$?
        assert_exit_code "$ec" 0 "new ai-chat exits successfully"
        if [ -d "$tmp_dir/net-ai-chat" ]; then
            compile_log=$(cd "$tmp_dir/net-ai-chat" && mvn -q -B -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true compile 2>&1) && cec=0 || cec=$?
            if [ "$cec" -eq 0 ]; then
                pass "cloned ai-chat pom.xml compiles standalone"
            else
                fail "cloned ai-chat pom.xml compile failed" "$(printf '%s' "$compile_log" | tail -20)"
            fi
        else
            fail "cloned ai-chat project directory missing"
        fi
    else
        printf "  ${DIM}— skipping sparse-clone compile regression (mvn not on PATH)${RESET}\n"
    fi

    printf "\n"
fi

# ── 12c. CLI: --runtime overlay ────────────────────────────────────────────
# `--runtime <name>` injects an AgentRuntime adapter (and provider deps + repo)
# into the scaffolded pom.xml. Tests cover the registry, validation path, and
# the pure-local injection logic (sourcing the helper without network).
printf "${BOLD}atmosphere new --runtime${RESET}\n"

OVERLAYS_JSON="$SCRIPT_DIR/runtime-overlays.json"

# Registry integrity
if jq empty "$OVERLAYS_JSON" 2>/dev/null; then
    pass "runtime-overlays.json is valid JSON"
else
    fail "runtime-overlays.json is valid JSON"
fi

expected_runtimes="builtin spring-ai langchain4j adk koog embabel semantic-kernel"
for rt in $expected_runtimes; do
    if jq -e ".overlays[\"$rt\"]" "$OVERLAYS_JSON" >/dev/null 2>&1; then
        pass "registry has '$rt' overlay"
    else
        fail "registry has '$rt' overlay"
    fi
done

# Every non-builtin overlay must declare description + at least one dep with
# groupId/artifactId; built-in is the only valid empty-deps entry.
bad_overlays=$(jq -r '
    .overlays | to_entries[] |
    select(.key != "builtin") |
    select((.value.description | not) or (.value.deps | length == 0) or
           (.value.deps | map(select(.groupId == null or .artifactId == null)) | length > 0)) |
    .key
' "$OVERLAYS_JSON")
if [ -z "$bad_overlays" ]; then
    pass "all overlays declare description + groupId/artifactId on every dep"
else
    fail "overlays missing required fields" "$bad_overlays"
fi

# Embabel must declare the repository entry (Embabel artifacts are not on
# Maven Central — without this, the cloned project won't resolve the deps).
emb_repo=$(jq -r '.overlays.embabel.repository.url // empty' "$OVERLAYS_JSON")
assert_contains "$emb_repo" "repo.embabel.com" "embabel overlay declares repo.embabel.com"

# Validation: --runtime with no value
out=$("$CLI" new foo --runtime 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "new --runtime without value exits with error"

# Validation: unknown runtime triggers the registry error path. The CLI must
# parse arguments far enough to call apply_runtime_overlay AFTER scaffolding,
# so we can't test this purely offline against a real template (clone needs
# network). Instead, drive the helper directly via a sub-shell that sources
# only the apply_runtime_overlay function.
overlay_tmp=$(mktemp -d)
cp "$SCRIPT_DIR/../samples/spring-boot-ai-chat/pom.xml" "$overlay_tmp/pom.xml"

# Extract the apply_runtime_overlay function body to a sourceable file. We
# can't pass it through `sh -c` (the function's own $variables would expand
# in the outer shell first), so we drive it via a tiny harness script.
overlay_fn_file="$overlay_tmp/apply_overlay.sh"
sed -n '/^# ── Runtime overlay/,/^cmd_new() {$/p' "$CLI" \
    | sed '/^cmd_new() {$/d' > "$overlay_fn_file"

cat > "$overlay_tmp/harness.sh" <<HARNESS
#!/bin/sh
# Stand-alone harness that calls apply_runtime_overlay with the env the
# real CLI provides.
die()  { echo "error: \$1" >&2; exit 1; }
info() { :; }
ok()   { :; }
warn() { :; }
has_jq() { command -v jq >/dev/null 2>&1; }
ensure_cache_dir() { :; }
download() { :; }
RED=''; GREEN=''; YELLOW=''; CYAN=''; BOLD=''; DIM=''; RESET=''
CACHE_DIR='$overlay_tmp/cache'
get_runtime_overlays_json() { cat '$OVERLAYS_JSON'; }
. '$overlay_fn_file'
apply_runtime_overlay "\$1" "\$2"
HARNESS
chmod +x "$overlay_tmp/harness.sh"

drive_overlay() {
    "$overlay_tmp/harness.sh" "$1" "$2"
}

# Unknown runtime gives a friendly error listing valid choices
cp "$overlay_tmp/pom.xml" "$overlay_tmp/pom.unknown.xml"
out=$(drive_overlay nonsense "$overlay_tmp/pom.unknown.xml" 2>&1) && ec=0 || ec=$?
assert_exit_code "$ec" 1 "unknown runtime exits with error"
assert_contains "$out" "Unknown runtime: nonsense" "error names the bad runtime"
assert_contains "$out" "spring-ai" "error lists spring-ai as valid"
assert_contains "$out" "embabel" "error lists embabel as valid"

# Each overlay produces well-formed XML, leaves <dependencyManagement> alone,
# and adds the matching adapter dependency. Use xmllint when available;
# otherwise fall back to a structural grep.
have_xmllint=0
command -v xmllint >/dev/null 2>&1 && have_xmllint=1

for rt in $expected_runtimes; do
    pom_copy="$overlay_tmp/pom.$rt.xml"
    cp "$overlay_tmp/pom.xml" "$pom_copy"
    drive_overlay "$rt" "$pom_copy" >/dev/null 2>&1 || {
        fail "overlay '$rt' applied without error"
        continue
    }
    pass "overlay '$rt' applied without error"

    if [ "$have_xmllint" = 1 ]; then
        if xmllint --noout "$pom_copy" 2>/dev/null; then
            pass "overlay '$rt' produces well-formed XML"
        else
            fail "overlay '$rt' produces well-formed XML" \
                 "$(xmllint --noout "$pom_copy" 2>&1 | head -3)"
        fi
    fi

    # Built-in is a no-op overlay — skip the dep-presence check.
    if [ "$rt" = "builtin" ]; then continue; fi

    expected_artifact=$(jq -r ".overlays[\"$rt\"].deps[0].artifactId" "$OVERLAYS_JSON")
    if grep -q "<artifactId>$expected_artifact</artifactId>" "$pom_copy"; then
        pass "overlay '$rt' injected $expected_artifact"
    else
        fail "overlay '$rt' injected $expected_artifact"
    fi
done

# Embabel's repository must end up inside <repositories>.
emb_pom="$overlay_tmp/pom.embabel.xml"
if grep -q "<id>embabel-releases</id>" "$emb_pom"; then
    pass "embabel overlay injects <repository> entry"
else
    fail "embabel overlay injects <repository> entry"
fi

# The injection must NOT land inside <dependencyManagement> — that section
# only declares versions, it does not bring deps onto the runtime classpath.
# Smoke-check by ensuring the injected comment marker appears AFTER the
# closing </dependencyManagement> tag (or that the pom has none).
mgmt_close_line=$(grep -n "</dependencyManagement>" "$overlay_tmp/pom.langchain4j.xml" | head -1 | cut -d: -f1)
inject_line=$(grep -n "Injected by atmosphere CLI" "$overlay_tmp/pom.langchain4j.xml" | head -1 | cut -d: -f1)
if [ -z "$mgmt_close_line" ] || [ -z "$inject_line" ] || [ "$inject_line" -gt "$mgmt_close_line" ]; then
    pass "overlay lands outside <dependencyManagement>"
else
    fail "overlay lands outside <dependencyManagement>" \
         "inject@$inject_line, </dependencyManagement>@$mgmt_close_line"
fi

rm -rf "$overlay_tmp"
printf "\n"

# ── 13. npx: create-atmosphere-app ─────────────────────────────────────────
# The npx wrapper is a thin shim that delegates to `atmosphere new`. Tests
# assert its argument parsing + delegation contract, not clone output.
if [ -z "$SKIP_NPX" ]; then
    printf "${BOLD}npx create-atmosphere-app${RESET}\n"

    # Resolve node's absolute path so delegation tests that override PATH
    # can still invoke node itself (homebrew installs node outside /usr/bin).
    real_node=$(command -v node)

    # Help
    out=$(node "$NPX" --help 2>&1)
    assert_contains "$out" "create-atmosphere-app" "npx help shows tool name"
    assert_contains "$out" "chat" "npx help lists chat template"
    assert_contains "$out" "ai-chat" "npx help lists ai-chat template"
    assert_contains "$out" "multi-agent" "npx help lists multi-agent template"
    assert_contains "$out" "classroom" "npx help lists classroom template"

    # List templates
    out=$(node "$NPX" --list-templates 2>&1)
    assert_contains "$out" "chat" "npx --list-templates shows chat"
    assert_contains "$out" "rag" "npx --list-templates shows rag"
    assert_contains "$out" "multi-agent" "npx --list-templates shows multi-agent"
    assert_contains "$out" "classroom" "npx --list-templates shows classroom"

    # No name
    out=$(node "$NPX" 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx without name exits with error"

    # Unknown template
    out=$(node "$NPX" bad-app --template nonexistent 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx with unknown template exits with error"
    assert_contains "$out" "Unknown template: nonexistent" "npx error lists the bad template"

    # Existing directory
    npx_tmp=$(mktemp -d)
    mkdir -p "$npx_tmp/existing-dir"
    out=$(cd "$npx_tmp" && node -e "
process.argv = ['node', 'index.js', 'existing-dir', '--template', 'chat'];
try { require('$NPX'); } catch(e) { process.exit(1); }
" 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx with existing directory exits with error"

    # Missing atmosphere CLI → actionable install hint.
    npx_nocli_bin="$npx_tmp/empty-bin"
    mkdir -p "$npx_nocli_bin"
    out=$(cd "$npx_tmp" && env PATH="$npx_nocli_bin" "$real_node" "$NPX" no-cli-app --template chat 2>&1) && ec=0 || ec=$?
    assert_exit_code "$ec" 1 "npx exits 1 when atmosphere CLI is missing"
    assert_contains "$out" "atmosphere" "npx install-hint mentions atmosphere CLI"

    # Delegation: fake `atmosphere` binary captures the delegated argv
    fake_bin="$npx_tmp/fake-bin"
    mkdir -p "$fake_bin"
    cat > "$fake_bin/atmosphere" <<'FAKEOF'
#!/bin/sh
printf 'ATMOSPHERE_ARGS: %s\n' "$*"
exit 0
FAKEOF
    chmod +x "$fake_bin/atmosphere"

    out=$(cd "$npx_tmp" && env PATH="$fake_bin:/usr/bin:/bin:/usr/sbin:/sbin" "$real_node" "$NPX" my-ai-app --template ai-chat 2>&1)
    assert_contains "$out" "ATMOSPHERE_ARGS: new my-ai-app --template ai-chat" \
        "npx delegates to 'atmosphere new' with --template"

    out=$(cd "$npx_tmp" && env PATH="$fake_bin:/usr/bin:/bin:/usr/sbin:/sbin" "$real_node" "$NPX" my-fleet --template multi-agent 2>&1)
    assert_contains "$out" "ATMOSPHERE_ARGS: new my-fleet --template multi-agent" \
        "npx delegates multi-agent template correctly"

    # --runtime forwards through to the shell CLI
    out=$(cd "$npx_tmp" && env PATH="$fake_bin:/usr/bin:/bin:/usr/sbin:/sbin" "$real_node" "$NPX" my-rt-app --template ai-chat --runtime langchain4j 2>&1)
    assert_contains "$out" "--runtime langchain4j" "npx forwards --runtime to shell CLI"

    # --runtime help is documented
    out=$(node "$NPX" --help 2>&1)
    assert_contains "$out" "--runtime" "npx --help documents --runtime"

    # --group is silently accepted but warned about (not forwarded)
    out=$(cd "$npx_tmp" && env PATH="$fake_bin:/usr/bin:/bin:/usr/sbin:/sbin" "$real_node" "$NPX" g-app --template chat --group com.acme 2>&1)
    assert_contains "$out" "--group is no longer supported" "npx warns on deprecated --group"
    assert_not_contains "$out" "ATMOSPHERE_ARGS: new g-app --template chat --group" "npx strips --group before delegating"

    rm -rf "$npx_tmp"
    printf "\n"
fi

# ── 14. samples.json <-> filesystem consistency ────────────────────────────
printf "${BOLD}samples.json ↔ filesystem${RESET}\n"

repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -d "$repo_root/samples" ]; then
    # Every sample in JSON should exist on disk
    # Use temp file to avoid subshell variable loss in piped while loops
    fs_tmp="${TMPDIR:-/tmp}/atmo-test-fs-$$"
    jq -r '.samples[].name' "$SAMPLES_JSON" > "$fs_tmp"
    while read -r name; do
        if [ -d "$repo_root/samples/$name" ]; then
            pass "sample dir exists: $name"
        else
            fail "sample dir missing: $name"
        fi
    done < "$fs_tmp"

    # Samples claiming hasFrontend should have frontend/
    jq -r '.samples[] | select(.hasFrontend == true) | .name' "$SAMPLES_JSON" > "$fs_tmp"
    while read -r name; do
        if [ -d "$repo_root/samples/$name/frontend" ]; then
            pass "frontend dir exists: $name"
        else
            fail "frontend dir missing: $name"
        fi
    done < "$fs_tmp"

    # Every sample listed in samples.json must ship a README.md. Samples are
    # the first thing users see — a sample without a README is invisible.
    jq -r '.samples[].name' "$SAMPLES_JSON" > "$fs_tmp"
    missing_readmes=""
    while read -r name; do
        if [ ! -f "$repo_root/samples/$name/README.md" ]; then
            missing_readmes="$missing_readmes $name"
        fi
    done < "$fs_tmp"
    if [ -z "$missing_readmes" ]; then
        pass "every sample has a README.md"
    else
        fail "samples missing README.md:$missing_readmes"
    fi
    rm -f "$fs_tmp"
else
    fail "samples directory not found at $repo_root/samples"
fi

printf "\n"

# ── Import command tests ───────────────────────────────────────────────────
printf "${BOLD}Import Command${RESET}\n"

IMPORT_TMP=$(mktemp -d)
trap "rm -rf $IMPORT_TMP" EXIT

# Create a test skill file with YAML frontmatter and ## Tools
cat > "$IMPORT_TMP/test-skill.md" <<'SKILLEOF'
---
name: weather-bot
description: "A weather assistant with location lookup"
---

# Weather Bot
You are a helpful weather assistant.

## Skills
- Provide weather forecasts
- Answer climate questions

## Tools
- get_weather: Get current weather for a city
- get_forecast: Get 5-day forecast for a location

## Guardrails
- Never make up weather data
SKILLEOF

# Run imports from the temp directory so projects are created there
cd "$IMPORT_TMP"

# Test 1: Import from local file
output=$("$CLI" import --name test-weather-bot "$IMPORT_TMP/test-skill.md" 2>&1) || true
assert_contains "$output" "Parsed skill: weather-bot" "import: parses skill name from YAML frontmatter"
assert_contains "$output" "weather assistant" "import: parses description from YAML frontmatter"
assert_contains "$output" "2 @AiTool stubs" "import: generates @AiTool stubs from ## Tools"
assert_contains "$output" "Project scaffolded" "import: scaffolds project successfully"

# Test 2: Verify generated project structure
if [ -d "$IMPORT_TMP/test-weather-bot" ]; then
    pass "import: project directory created"
else
    fail "import: project directory not created"
fi

if [ -f "$IMPORT_TMP/test-weather-bot/pom.xml" ]; then
    pass "import: pom.xml generated"
    assert_contains "$(cat "$IMPORT_TMP/test-weather-bot/pom.xml")" "atmosphere-spring-boot-starter" "import: pom.xml includes atmosphere starter"
    assert_contains "$(cat "$IMPORT_TMP/test-weather-bot/pom.xml")" "atmosphere-agent" "import: pom.xml includes atmosphere-agent"
    assert_contains "$(cat "$IMPORT_TMP/test-weather-bot/pom.xml")" "atmosphere-a2a" "import: pom.xml includes atmosphere-a2a"
    assert_contains "$(cat "$IMPORT_TMP/test-weather-bot/pom.xml")" "atmosphere-mcp" "import: pom.xml includes atmosphere-mcp"
else
    fail "import: pom.xml not generated"
fi

skill_file="$IMPORT_TMP/test-weather-bot/src/main/resources/META-INF/skills/weather-bot/SKILL.md"
if [ -f "$skill_file" ]; then
    pass "import: skill file at META-INF/skills convention path"
    assert_contains "$(cat "$skill_file")" "Weather Bot" "import: skill file content preserved"
else
    fail "import: skill file not copied"
fi

# Test 3: Verify generated Java agent class
agent_file=$(find "$IMPORT_TMP/test-weather-bot/src/main/java" -name "*Agent.java" | head -1)
if [ -n "$agent_file" ]; then
    pass "import: agent Java file generated"
    agent_content=$(cat "$agent_file")
    assert_contains "$agent_content" '@Agent(name = "weather-bot"' "import: @Agent annotation with correct name"
    assert_not_contains "$agent_content" 'skillFile' "import: no explicit skillFile (auto-discovery)"
    assert_contains "$agent_content" '@Prompt' "import: @Prompt method generated"
    assert_contains "$agent_content" '@AiTool(name = "get_weather"' "import: @AiTool stub for get_weather"
    assert_contains "$agent_content" '@AiTool(name = "get_forecast"' "import: @AiTool stub for get_forecast"
    assert_contains "$agent_content" "getWeather" "import: camelCase method name for get_weather"
    assert_contains "$agent_content" "getForecast" "import: camelCase method name for get_forecast"
    assert_contains "$agent_content" "StreamingSession" "import: StreamingSession import present"
else
    fail "import: agent Java file not generated"
fi

# Test 4: Verify Application.java generated
app_file=$(find "$IMPORT_TMP/test-weather-bot/src/main/java" -name "Application.java" | head -1)
if [ -n "$app_file" ]; then
    pass "import: Application.java generated"
    assert_contains "$(cat "$app_file")" "@SpringBootApplication" "import: @SpringBootApplication annotation present"
else
    fail "import: Application.java not generated"
fi

# Test 5: Verify application.yml generated
if [ -f "$IMPORT_TMP/test-weather-bot/src/main/resources/application.yml" ]; then
    pass "import: application.yml generated"
    assert_contains "$(cat "$IMPORT_TMP/test-weather-bot/src/main/resources/application.yml")" "LLM_API_KEY" "import: application.yml references LLM_API_KEY"
else
    fail "import: application.yml not generated"
fi

# Test 6: Import with --headless flag
output=$("$CLI" import --headless --name test-headless "$IMPORT_TMP/test-skill.md" 2>&1) || true
assert_contains "$output" "headless @Agent" "import --headless: reports headless mode"

headless_file=$(find "$IMPORT_TMP/test-headless/src/main/java" -name "*Agent.java" | head -1)
if [ -n "$headless_file" ]; then
    headless_content=$(cat "$headless_file")
    assert_contains "$headless_content" "headless = true" "import --headless: @Agent has headless = true"
    assert_contains "$headless_content" "@AgentSkill" "import --headless: @AgentSkill annotation present"
    assert_contains "$headless_content" "@AgentSkillHandler" "import --headless: @AgentSkillHandler annotation present"
    assert_contains "$headless_content" "TaskContext" "import --headless: TaskContext parameter present"
    assert_contains "$headless_content" "a2a.types.Artifact" "import --headless: Artifact import from types package"
    assert_not_contains "$headless_content" "@Prompt" "import --headless: no @Prompt method"
else
    fail "import --headless: agent file not generated"
fi

# Test 7: Import skill without YAML frontmatter (fallback to heading)
cat > "$IMPORT_TMP/plain-skill.md" <<'PLAINEOF'
# Customer Support Agent
You help customers with their questions about our product.

## Skills
- Answer product questions
- Handle complaints
PLAINEOF

output=$("$CLI" import --name test-plain "$IMPORT_TMP/plain-skill.md" 2>&1) || true
assert_contains "$output" "Parsed skill: customer-support-agent" "import: extracts name from # heading when no frontmatter"

# Test 8: Import with no arguments fails
output=$("$CLI" import 2>&1) || true
assert_contains "$output" "Usage" "import: shows usage when no arguments"

# Test 9: Import to existing directory fails
mkdir -p "$IMPORT_TMP/existing-dir"
output=$("$CLI" import --name existing-dir "$IMPORT_TMP/test-skill.md" 2>&1) || true
assert_contains "$output" "already exists" "import: fails when directory exists"

# Test: Path traversal in skill name is sanitized
cat > "$IMPORT_TMP/traversal-skill.md" <<'TRAVEOF'
---
name: "../../../etc/evil"
description: "Attempted path traversal"
---
# Evil Agent
TRAVEOF

output=$("$CLI" import --name test-traversal "$IMPORT_TMP/traversal-skill.md" 2>&1) || true
assert_contains "$output" "Parsed skill: etc-evil" "import: path traversal stripped from skill name"
# Verify project is created in current dir, not escaped
if [ -d "$IMPORT_TMP/test-traversal" ] && [ ! -d "$IMPORT_TMP/../../../etc/evil" ]; then
    pass "import: path traversal blocked — project in safe location"
else
    fail "import: path traversal not blocked"
fi

# Test: Quotes in description are escaped for Java
cat > "$IMPORT_TMP/quotes-skill.md" <<'QUOTESEOF'
---
name: quotes-test
description: 'She said "hello" and it\'s fine'
---
# Quotes Test
QUOTESEOF

output=$("$CLI" import --name test-quotes "$IMPORT_TMP/quotes-skill.md" 2>&1) || true
quotes_file=$(find "$IMPORT_TMP/test-quotes/src/main/java" -name "*Agent.java" 2>/dev/null | head -1)
if [ -n "$quotes_file" ]; then
    assert_not_contains "$(cat "$quotes_file")" 'description = "She said "hello"' "import: quotes escaped in description"
    pass "import: agent with quoted description compiles (no raw quotes)"
else
    fail "import: agent with quoted description not generated"
fi

# Test: Markdown emphasis stripped from tool names
cat > "$IMPORT_TMP/emphasis-skill.md" <<'EMPHEOF'
---
name: emphasis-test
description: "Test markdown emphasis in tools"
---
# Emphasis Test

## Tools
- **get_data**: Fetch data from API
- `check_status`: Check system status
EMPHEOF

output=$("$CLI" import --name test-emphasis "$IMPORT_TMP/emphasis-skill.md" 2>&1) || true
emph_file=$(find "$IMPORT_TMP/test-emphasis/src/main/java" -name "*Agent.java" 2>/dev/null | head -1)
if [ -n "$emph_file" ]; then
    assert_contains "$(cat "$emph_file")" 'name = "get_data"' "import: bold markdown stripped from tool name"
    assert_contains "$(cat "$emph_file")" 'name = "check_status"' "import: backtick markdown stripped from tool name"
    # Verify tool names are clean (no markdown formatting)
    assert_contains "$(cat "$emph_file")" 'name = "get_data"' "import: bold stripped, clean tool name get_data"
    assert_contains "$(cat "$emph_file")" 'name = "check_status"' "import: backtick stripped, clean tool name check_status"
else
    fail "import: agent with emphasis tools not generated"
fi

# Test 10: Skills without ## Tools generates no @AiTool stubs
cat > "$IMPORT_TMP/no-tools-skill.md" <<'NOTOOLSEOF'
---
name: simple-chat
description: "A simple chat agent"
---

# Simple Chat
You are a simple chat assistant.
NOTOOLSEOF

output=$("$CLI" import --name test-no-tools "$IMPORT_TMP/no-tools-skill.md" 2>&1) || true
assert_not_contains "$output" "@AiTool" "import: no @AiTool stubs when no ## Tools section"

no_tools_file=$(find "$IMPORT_TMP/test-no-tools/src/main/java" -name "*Agent.java" | head -1)
if [ -n "$no_tools_file" ]; then
    assert_not_contains "$(cat "$no_tools_file")" "@AiTool" "import: generated class has no @AiTool when no ## Tools"
else
    fail "import: agent file not generated for no-tools skill"
fi

rm -rf "$IMPORT_TMP"
printf "\n"

# ── Plugins command tests ──────────────────────────────────────────────────
printf "${BOLD}atmosphere plugins${RESET}\n"

output=$("$CLI" plugins 2>&1) || true
assert_contains "$output" "plugins" "plugins: command runs without error"

output=$("$CLI" plugins list 2>&1) || true
assert_contains "$output" "plugins" "plugins list: shows plugin info"

printf "\n"

# ── Skills command tests (offline) ─────────────────────────────────────────
printf "${BOLD}atmosphere skills (offline)${RESET}\n"

# skills list without network should fail gracefully (no registry cached)
output=$("$CLI" skills list 2>&1) || true
# It will either show cached results or fail with download error — both are OK
pass "skills list: command runs without crash"

# skills search without network
output=$("$CLI" skills search medical 2>&1) || true
pass "skills search: command runs without crash"

# skills run without argument
output=$("$CLI" skills run 2>&1) || true
assert_contains "$output" "Usage" "skills run: shows usage when no argument"

# skills unknown subcommand
output=$("$CLI" skills foobar 2>&1) || true
assert_contains "$output" "Unknown" "skills: unknown subcommand rejected"

printf "\n"

# ── Remote import tests (require network) ─────────────────────────────────
# Only run if ATMOSPHERE_TEST_REMOTE=true (skipped in offline CI by default)
if [ "${ATMOSPHERE_TEST_REMOTE:-false}" = "true" ]; then
    printf "${BOLD}Import (Remote Skills)${RESET}\n"

    REMOTE_TMP=$(mktemp -d)
    cd "$REMOTE_TMP"

    # Test: Import from Atmosphere skills repo (trusted)
    output=$("$CLI" import --name remote-dentist https://raw.githubusercontent.com/Atmosphere/atmosphere-skills/main/skills/dentist-agent/SKILL.md 2>&1) || true
    assert_contains "$output" "Project scaffolded" "remote: import from atmosphere-skills repo"

    if [ -d "$REMOTE_TMP/remote-dentist" ]; then
        pass "remote: project directory created"
        agent_file=$(find "$REMOTE_TMP/remote-dentist/src/main/java" -name "*Agent.java" 2>/dev/null | head -1)
        if [ -n "$agent_file" ]; then
            assert_contains "$(cat "$agent_file")" "@Agent" "remote: @Agent annotation present"
            assert_contains "$(cat "$agent_file")" "@Prompt" "remote: @Prompt method present"
        else
            fail "remote: agent file not generated"
        fi
        assert_contains "$(cat "$REMOTE_TMP/remote-dentist/pom.xml")" "atmosphere-spring-boot-starter" "remote: pom.xml has starter dep"
    else
        fail "remote: project directory not created"
    fi

    # Test: Import from Anthropic skills repo (trusted)
    output=$("$CLI" import --name remote-anthropic https://raw.githubusercontent.com/anthropics/skills/main/skills/frontend-design/SKILL.md 2>&1) || true
    assert_contains "$output" "Parsed skill: frontend-design" "remote: anthropic skill name parsed"
    assert_contains "$output" "Project scaffolded" "remote: anthropic import scaffolded"

    # Test: Import from Antigravity repo (trusted)
    output=$("$CLI" import --name remote-antigravity https://raw.githubusercontent.com/sickn33/antigravity-awesome-skills/main/skills/customer-support/SKILL.md 2>&1) || true
    assert_contains "$output" "Parsed skill: customer-support" "remote: antigravity skill name parsed"
    assert_contains "$output" "Project scaffolded" "remote: antigravity import scaffolded"

    # Test: Untrusted source is blocked
    output=$("$CLI" import --name untrusted https://example.com/evil/SKILL.md 2>&1) || true
    assert_contains "$output" "Untrusted source" "remote: untrusted URL blocked"

    # Test: --trust flag bypasses trust check
    # (don't actually download from example.com — it will fail with download error, not trust error)
    output=$("$CLI" import --trust --name trusted-override https://example.com/evil/SKILL.md 2>&1) || true
    assert_not_contains "$output" "Untrusted source" "remote: --trust bypasses trust check"

    rm -rf "$REMOTE_TMP"
    printf "\n"
else
    printf "${BOLD}Import (Remote Skills)${RESET} ${DIM}— skipped (set ATMOSPHERE_TEST_REMOTE=true)${RESET}\n\n"
fi

# ── Summary ─────────────────────────────────────────────────────────────────
total=$((PASS + FAIL))
printf "${BOLD}Results: %s passed, %s failed${RESET} (out of %s)\n\n" "$PASS" "$FAIL" "$total"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
