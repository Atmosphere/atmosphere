#!/usr/bin/env bash
# Integration tests for `atmosphere compose` — generates multi-agent projects and verifies they compile.
#
# Usage: bash generator/test-compose.sh
#
# Prerequisites: jbang, Java 21+, Maven (via mvnw)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR=""

cleanup() {
    if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

WORK_DIR="$(mktemp -d)"
PASS=0
FAIL=0

green()  { printf '\033[32m%s\033[0m\n' "$*"; }
red()    { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

# ---- Create test skill files ----

create_skills() {
    local dir="$1"

    mkdir -p "$dir/coordinator" "$dir/researcher" "$dir/analyst"

    cat > "$dir/coordinator/SKILL.md" <<'SKILLEOF'
---
name: test-lead
description: "Test coordinator"
role: coordinator
---
# Test Lead
You coordinate.

## Fleet
- researcher: Researches topics
- analyst: Analyzes data

## Workflow
researcher -> analyst
SKILLEOF

    cat > "$dir/researcher/SKILL.md" <<'SKILLEOF'
---
name: researcher
description: "Researches topics"
---
# Researcher
You research.

## Tools
- search_web: Search the web
- read_docs: Read documentation

## Guardrails
- Cite sources
SKILLEOF

    cat > "$dir/analyst/SKILL.md" <<'SKILLEOF'
---
name: analyst
description: "Analyzes data"
---
# Analyst
You analyze.
SKILLEOF
}

# ---- Test variants ----

run_test() {
    local label="$1"
    local extra_args="$2"
    local expected_files="$3"

    echo ""
    yellow "=== Test: $label ==="

    local skills_dir="$WORK_DIR/skills-$label"
    local output_dir="$WORK_DIR/output-$label"
    create_skills "$skills_dir"

    # 1. Extract versions from root POM
    # Use the versions from the local Maven repo (most reliable)
    local atmo_version sb_version
    atmo_version=$(ls -d "$HOME/.m2/repository/org/atmosphere/atmosphere-runtime/"*-SNAPSHOT 2>/dev/null | sort -V | tail -1 | xargs basename 2>/dev/null || echo "4.0.29-SNAPSHOT")
    sb_version="4.0.5"

    echo "  Versions: atmosphere=$atmo_version, spring-boot=$sb_version"

    # 2. Generate project
    echo "  Generating project..."
    if ! jbang "$SCRIPT_DIR/ComposeGenerator.java" \
        --name "test-$label" \
        --skills "$skills_dir/coordinator/SKILL.md,$skills_dir/researcher/SKILL.md,$skills_dir/analyst/SKILL.md" \
        --atmosphere-version "$atmo_version" \
        --spring-boot-version "$sb_version" \
        --output "$output_dir" \
        $extra_args > /dev/null 2>&1; then
        red "  FAIL: generation failed"
        FAIL=$((FAIL + 1))
        return
    fi

    # 2. Verify expected files
    echo "  Checking expected files..."
    IFS=',' read -ra files <<< "$expected_files"
    for f in "${files[@]}"; do
        if ! find "$output_dir" -name "$f" -print -quit | grep -q .; then
            red "  FAIL: expected file '$f' not found"
            FAIL=$((FAIL + 1))
            return
        fi
    done

    # 3. Verify parent POM has correct modules
    echo "  Checking parent POM modules..."
    if ! grep -q '<module>coordinator</module>' "$output_dir/pom.xml"; then
        red "  FAIL: parent POM missing coordinator module"
        FAIL=$((FAIL + 1))
        return
    fi
    if ! grep -q '<module>agents/researcher</module>' "$output_dir/pom.xml"; then
        red "  FAIL: parent POM missing researcher agent module"
        FAIL=$((FAIL + 1))
        return
    fi

    # 4. Verify coordinator has @Coordinator and @Fleet
    echo "  Checking coordinator annotations..."
    local coord_java
    coord_java=$(find "$output_dir/coordinator" -name "*.java" -path "*/TestLead*" -print -quit)
    if [[ -z "$coord_java" ]]; then
        coord_java=$(find "$output_dir/coordinator" -name "*.java" ! -name "Application.java" -print -quit)
    fi
    if [[ -n "$coord_java" ]]; then
        if ! grep -q "@Coordinator" "$coord_java"; then
            red "  FAIL: coordinator missing @Coordinator"
            FAIL=$((FAIL + 1))
            return
        fi
        if ! grep -q "@Fleet" "$coord_java"; then
            red "  FAIL: coordinator missing @Fleet"
            FAIL=$((FAIL + 1))
            return
        fi
        if ! grep -q "AgentFleet" "$coord_java"; then
            red "  FAIL: coordinator missing AgentFleet"
            FAIL=$((FAIL + 1))
            return
        fi
    else
        red "  FAIL: no coordinator Java file found"
        FAIL=$((FAIL + 1))
        return
    fi

    # 5. Verify agents have @Agent + @Prompt + session.stream
    echo "  Checking agent annotations..."
    local agent_java
    agent_java=$(find "$output_dir/agents/researcher" -name "*.java" ! -name "Application.java" -print -quit)
    if [[ -n "$agent_java" ]]; then
        if ! grep -q "@Agent" "$agent_java"; then
            red "  FAIL: agent missing @Agent"
            FAIL=$((FAIL + 1))
            return
        fi
        if ! grep -q "@Prompt" "$agent_java"; then
            red "  FAIL: agent missing @Prompt (should delegate to LLM)"
            FAIL=$((FAIL + 1))
            return
        fi
        if ! grep -q "session.stream" "$agent_java"; then
            red "  FAIL: agent missing session.stream() call"
            FAIL=$((FAIL + 1))
            return
        fi
        if ! grep -q "@AiTool" "$agent_java"; then
            red "  FAIL: researcher agent missing @AiTool (has ## Tools)"
            FAIL=$((FAIL + 1))
            return
        fi
    else
        red "  FAIL: no agent Java file found"
        FAIL=$((FAIL + 1))
        return
    fi

    # 6. Verify skill files were copied
    echo "  Checking skill files..."
    if ! find "$output_dir/coordinator" -name "skill.md" -print -quit | grep -q .; then
        red "  FAIL: coordinator skill file not copied"
        FAIL=$((FAIL + 1))
        return
    fi
    if ! find "$output_dir/agents/researcher" -name "skill.md" -print -quit | grep -q .; then
        red "  FAIL: researcher skill file not copied"
        FAIL=$((FAIL + 1))
        return
    fi

    # 7. Compile the generated project
    echo "  Compiling generated project (coordinator module)..."
    # Copy Maven wrapper from the repo
    cp "$REPO_ROOT/mvnw" "$output_dir/" 2>/dev/null || true
    cp "$REPO_ROOT/mvnw.cmd" "$output_dir/" 2>/dev/null || true
    cp -r "$REPO_ROOT/.mvn" "$output_dir/" 2>/dev/null || true
    chmod +x "$output_dir/mvnw" 2>/dev/null || true

    # single-jar needs 'install' for inter-module deps; docker-compose only needs 'compile'
    local mvn_goal="compile"
    if grep -q 'isSingleJar' <<< "$extra_args" || grep -q 'single-jar' <<< "$extra_args"; then
        mvn_goal="install -DskipTests"
    fi
    if ! (cd "$output_dir" && ./mvnw -B $mvn_goal -q 2>&1); then
        red "  FAIL: compilation failed"
        FAIL=$((FAIL + 1))
        return
    fi

    green "  PASS: $label"
    PASS=$((PASS + 1))
}

# ---- Main ----

echo "Atmosphere Compose Integration Tests"
echo "====================================="
echo "Working directory: $WORK_DIR"

# Test 1: A2A + docker-compose (default)
run_test "a2a-docker" \
    "--protocol a2a --deploy docker-compose --frontend none --ai builtin" \
    "pom.xml,docker-compose.yml,Dockerfile,README.md,Application.java,application.yml,skill.md"

# Test 2: A2A + single-jar
run_test "a2a-single" \
    "--protocol a2a --deploy single-jar --frontend none --ai builtin" \
    "pom.xml,README.md,Application.java,application.yml,skill.md"

# Test 3: Local protocol
run_test "local" \
    "--protocol local --deploy single-jar --frontend none --ai builtin" \
    "pom.xml,README.md,Application.java,application.yml,skill.md"

echo ""
echo "====================================="
echo "Results: $PASS passed, $FAIL failed"

if [[ $FAIL -gt 0 ]]; then
    red "FAILED"
    exit 1
fi
green "ALL PASSED"
