#!/usr/bin/env bash
#
# Regenerate per-runtime SKILLCARD.yaml manifests from canonical sources.
#
# Sources of truth (read-only):
#   1. .harness/capabilities.snapshot.json
#      — per-runtime expected_capabilities + language pinned by CapabilitySnapshotTest.
#   2. modules/<X>/pom.xml
#      — artifact group_id / artifact_id / version / packaging / description.
#   3. modules/<X>/src/main/resources/META-INF/services/org.atmosphere.ai.AgentRuntime
#      — fully-qualified implementation class registered with ServiceLoader.
#
# Output: modules/<X>/SKILLCARD.yaml (one per runtime registered via ServiceLoader).
#
# Why a per-runtime SKILLCARD when the snapshot already aggregates the same data:
# the snapshot is one file describing every runtime; SKILLCARD.yaml is a portable
# per-artifact manifest that travels with the jar. The shape is inspired by the
# agentskills.io spec popularised by NVIDIA's verified-agent-skills programme —
# a machine-readable trust document the consumer of a runtime adapter can read
# before adding the jar to their classpath. Drift between the snapshot and any
# SKILLCARD breaks SkillCardSnapshotTest in modules/ai-test, so prose claims
# about capabilities stay anchored to the runtime's pinned declaration.
#
# Not implemented in this revision (honest gaps):
#   - OpenSSF Model Signing (OMS) detached signatures — `signing.status: unsigned`
#     until a signing identity / CA is wired in. SKILLCARD.yaml carries the
#     placeholder so the slot is reserved.
#   - Curated risk / mitigation prose — runtime-specific risks ("requires
#     ANTHROPIC_API_KEY", "outbound HTTPS to api.openai.com") are not auto-derivable
#     and live in the module README until a separate curation pass.
#   - Conformance with a registered agentskills.io schema version — we emit a
#     stable shape, but do not claim certification against the upstream spec.
#
# Usage:
#   ./scripts/regen-skillcards.sh           # write every SKILLCARD.yaml
#   ./scripts/regen-skillcards.sh --check   # exit 1 if any would change
#   ./scripts/regen-skillcards.sh --stdout  # write all cards to stdout (debug)

set -euo pipefail

# C locale so awk numeric / sort order matches Java's TreeSet<String> code-point
# order — same rationale as scripts/regen-capability-snapshot.sh.
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MODE="write"
for arg in "$@"; do
    case "$arg" in
        --check)  MODE="check" ;;
        --stdout) MODE="stdout" ;;
        *) echo "regen-skillcards.sh: unknown arg $arg" >&2; exit 2 ;;
    esac
done

SNAPSHOT_PATH=".harness/capabilities.snapshot.json"
if [ ! -f "$SNAPSHOT_PATH" ]; then
    echo "regen-skillcards.sh: $SNAPSHOT_PATH missing — run ./scripts/regen-capability-snapshot.sh first" >&2
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "regen-skillcards.sh: jq is required to parse $SNAPSHOT_PATH" >&2
    exit 1
fi

SOURCE_REPO="https://github.com/Atmosphere/atmosphere"

# Locate every module that registers an AgentRuntime via ServiceLoader.
mapfile -t SERVICE_FILES < <(
    find modules -path '*/src/main/resources/META-INF/services/org.atmosphere.ai.AgentRuntime' \
        -not -path '*/target/*' \
        | sort
)

if [ ${#SERVICE_FILES[@]} -eq 0 ]; then
    echo "regen-skillcards.sh: no AgentRuntime ServiceLoader entries found under modules/" >&2
    exit 1
fi

# Extract a single XML element value from a pom.xml, ignoring elements nested
# inside <parent>. Awk-based so we avoid xmllint's namespace quirks and stay
# consistent with regen-capability-snapshot.sh's style.
pom_field() {
    local pom="$1" tag="$2"
    awk -v tag="$tag" '
        /<parent>/  { in_parent = 1 }
        /<\/parent>/ { in_parent = 0; next }
        in_parent { next }
        {
            # match <tag>value</tag> on a single line, allowing leading whitespace
            re = "<" tag ">[^<]*</" tag ">"
            if (match($0, re)) {
                v = substr($0, RSTART, RLENGTH)
                sub("^<" tag ">", "", v)
                sub("</" tag ">$", "", v)
                print v
                exit
            }
        }
    ' "$pom"
}

# Extract a tag from inside <parent>...</parent> in a pom.xml.
pom_parent_field() {
    local pom="$1" tag="$2"
    awk -v tag="$tag" '
        /<parent>/  { in_parent = 1; next }
        /<\/parent>/ { in_parent = 0 }
        in_parent {
            re = "<" tag ">[^<]*</" tag ">"
            if (match($0, re)) {
                v = substr($0, RSTART, RLENGTH)
                sub("^<" tag ">", "", v)
                sub("</" tag ">$", "", v)
                print v
                exit
            }
        }
    ' "$pom"
}

# Emit a single SKILLCARD YAML to stdout.
# Args: service_file (path to META-INF/services/org.atmosphere.ai.AgentRuntime)
emit_skillcard() {
    local service_file="$1"
    local module_dir
    module_dir="$(echo "$service_file" | awk -F'/src/main/' '{print $1}')"
    local pom="$module_dir/pom.xml"
    if [ ! -f "$pom" ]; then
        echo "regen-skillcards.sh: $pom missing" >&2
        return 1
    fi

    # ServiceLoader entries are one FQN per line; for modules/ai (which lists
    # BuiltInAgentRuntime + DemoAgentRuntime) we keep only entries that match
    # an item in the snapshot. Comments / blank lines are skipped.
    mapfile -t fqns < <(
        awk 'NF && !/^[[:space:]]*#/' "$service_file"
    )

    local snapshot_runtimes
    snapshot_runtimes=$(jq -r '.runtimes.items[].name' "$SNAPSHOT_PATH")

    local matched_fqn=""
    local matched_simple=""
    for fqn in "${fqns[@]}"; do
        local simple="${fqn##*.}"
        if echo "$snapshot_runtimes" | grep -qx "$simple"; then
            matched_fqn="$fqn"
            matched_simple="$simple"
            break
        fi
    done

    if [ -z "$matched_simple" ]; then
        # ServiceLoader registers a runtime not pinned in the snapshot — skip it,
        # don't fail. DemoAgentRuntime hits this path; that's expected.
        return 0
    fi

    local group_id artifact_id version packaging description
    group_id="$(pom_parent_field "$pom" groupId)"
    version="$(pom_parent_field "$pom" version)"
    artifact_id="$(pom_field "$pom" artifactId)"
    packaging="$(pom_field "$pom" packaging)"
    description="$(pom_field "$pom" description)"
    if [ -z "$packaging" ]; then packaging="jar"; fi
    if [ -z "$description" ]; then
        description="$matched_simple runtime adapter for Atmosphere Framework"
    fi
    if [ -z "$group_id" ] || [ -z "$version" ] || [ -z "$artifact_id" ]; then
        echo "regen-skillcards.sh: missing groupId/version/artifactId in $pom" >&2
        return 1
    fi

    # Snapshot lookup for capabilities + language + contract_test path.
    local snapshot_entry
    snapshot_entry=$(jq --arg n "$matched_simple" \
        '.runtimes.items[] | select(.name == $n)' "$SNAPSHOT_PATH")
    if [ -z "$snapshot_entry" ]; then
        echo "regen-skillcards.sh: $matched_simple not in snapshot" >&2
        return 1
    fi

    local language contract_test cap_count
    language=$(echo "$snapshot_entry" | jq -r '.language')
    contract_test=$(echo "$snapshot_entry" | jq -r '.contract_test')
    cap_count=$(echo "$snapshot_entry" | jq -r '.expected_capabilities | length')

    # Capabilities list — already alphabetical in the snapshot.
    local caps_yaml
    caps_yaml=$(echo "$snapshot_entry" \
        | jq -r '.expected_capabilities[] | "    - " + .')

    cat <<EOF
# Atmosphere runtime skill card — generated artifact, do not edit by hand.
# Regenerate with: ./scripts/regen-skillcards.sh
# Drift between this file and .harness/capabilities.snapshot.json breaks
# SkillCardSnapshotTest (mvn test) and pre-push validation.
#
# Signing convention follows the OpenSSF Model Signing project
# (https://github.com/sigstore/model-transparency) — the same envelope
# format NVIDIA's verified-agent-skills programme consumes. Cards on
# main are unsigned; \`.github/workflows/sign-skillcards.yml\` produces
# a Sigstore-keyless signature for every card on tag push and attaches
# the .sig files to the GitHub release + as workflow artifacts.
# Verify locally with:
#   ./scripts/verify-skillcards.sh \\
#       --identity \\
#       https://github.com/Atmosphere/atmosphere/.github/workflows/sign-skillcards.yml@refs/tags/vX.Y.Z \\
#       --identity-provider https://token.actions.githubusercontent.com

schema_version: 1
spec: atmosphere/skillcard/v1
status: unsigned

name: $matched_simple
language: $language

description: |
  $description

license:
  spdx: Apache-2.0

artifact:
  group_id: $group_id
  artifact_id: $artifact_id
  version: $version
  packaging: $packaging
  module_path: $module_dir

spi:
  interface: org.atmosphere.ai.AgentRuntime
  implementation: $matched_fqn
  service_loader: META-INF/services/org.atmosphere.ai.AgentRuntime

capabilities:
  count: $cap_count
  declared:
$caps_yaml

contract_test:
  path: $contract_test
  pins: expectedCapabilities()

provenance:
  source_repo: $SOURCE_REPO
  generated_by: scripts/regen-skillcards.sh
  source_snapshot: $SNAPSHOT_PATH

signing:
  status: unsigned
  envelope: openssf-model-signing/v1
  bundle_format: sigstore
  signature_file: SKILLCARD.yaml.sig
  signed_by_workflow: .github/workflows/sign-skillcards.yml
EOF
}

drift_files=()
written=0
skipped=0

for service_file in "${SERVICE_FILES[@]}"; do
    module_dir="$(echo "$service_file" | awk -F'/src/main/' '{print $1}')"
    skillcard_path="$module_dir/SKILLCARD.yaml"

    new_yaml="$(emit_skillcard "$service_file" || true)"
    if [ -z "$new_yaml" ]; then
        skipped=$((skipped + 1))
        continue
    fi

    case "$MODE" in
        stdout)
            printf '%s\n---\n' "$new_yaml"
            ;;
        check)
            if [ ! -f "$skillcard_path" ]; then
                drift_files+=("$skillcard_path (missing)")
                continue
            fi
            existing="$(cat "$skillcard_path")"
            if [ "$existing" != "$new_yaml" ]; then
                drift_files+=("$skillcard_path")
            fi
            ;;
        write)
            printf '%s\n' "$new_yaml" > "$skillcard_path"
            written=$((written + 1))
            ;;
    esac
done

emit_catalog_index() {
    # Build the repo-root SKILLCARDS.md catalog from the snapshot. Lists
    # every signed-runtime card with name, version, capability count,
    # signature status (signed/unsigned), and links to the card +
    # contract test. The catalog IS the index for OMS-aware verifiers;
    # daily synchronisation is git itself — every commit to main
    # propagates to every clone, no separate sync infrastructure.
    cat <<'HEADER'
# Atmosphere Skill Card Catalog

Generated artifact — regenerate with `./scripts/regen-skillcards.sh`.
This file is the index for every per-runtime `SKILLCARD.yaml` shipped
in this repository. Each row is a verifiable trust manifest plus its
OpenSSF Model Signing signature when one has been produced (tag-time
sigstore-keyless via `.github/workflows/sign-skillcards.yml`).

Distribution model: git itself is the daily sync — every push to
`main` propagates the cards (and any signatures committed alongside)
to every clone. Released signatures are also attached to the GitHub
release as workflow artifacts; the bundled jars carry the same files
at `META-INF/atmosphere/SKILLCARD.yaml[.sig]`, so downstream consumers
can verify integrity without re-fetching from this repository.

Inspired by NVIDIA's verified-agent-skills catalog
(<https://developer.nvidia.com/blog/nvidia-verified-agent-skills-provide-capability-governance-for-ai-agents/>);
the signature envelope is the same OpenSSF Model Signing format
NVIDIA uses (`https://github.com/sigstore/model-transparency`).

| Runtime | Module | Language | Capabilities | Card | Contract test | Signature |
|---------|--------|----------|--------------|------|---------------|-----------|
HEADER

    # Iterate snapshot runtimes alphabetically by name. For each, look
    # up the card path on disk (impl module derived from META-INF) and
    # the .sig presence.
    jq -r '.runtimes.items | sort_by(.name) | .[] | [.name, .module, .language, (.expected_capabilities|length|tostring), .contract_test] | @tsv' "$SNAPSHOT_PATH" \
        | while IFS=$'\t' read -r name snapshot_module language cap_count contract_test; do
            # Find the actual card location (impl module, NOT contract_test module).
            card_path=""
            for service_file in "${SERVICE_FILES[@]}"; do
                impl_module="$(echo "$service_file" | awk -F'/src/main/' '{print $1}')"
                candidate="$impl_module/SKILLCARD.yaml"
                if [ -f "$candidate" ] && grep -q "^name: $name$" "$candidate" 2>/dev/null; then
                    card_path="$candidate"
                    break
                fi
            done
            if [ -z "$card_path" ]; then
                card_path="(missing)"
            fi
            sig_path="${card_path}.sig"
            if [ -f "$sig_path" ]; then
                sig_cell="[signed]($sig_path)"
            else
                sig_cell="unsigned"
            fi
            echo "| \`$name\` | \`$snapshot_module\` | $language | $cap_count | [card]($card_path) | [test]($contract_test) | $sig_cell |"
        done
}

case "$MODE" in
    stdout)
        :
        ;;
    check)
        if [ ${#drift_files[@]} -gt 0 ]; then
            echo "regen-skillcards.sh: SKILLCARD.yaml drift in:" >&2
            for f in "${drift_files[@]}"; do
                echo "  $f" >&2
            done
            echo "" >&2
            echo "Regenerate with: ./scripts/regen-skillcards.sh" >&2
            exit 1
        fi
        # Also check SKILLCARDS.md catalog index freshness.
        if [ -f SKILLCARDS.md ]; then
            new_catalog="$(emit_catalog_index)"
            existing_catalog="$(cat SKILLCARDS.md)"
            if [ "$existing_catalog" != "$new_catalog" ]; then
                echo "regen-skillcards.sh: SKILLCARDS.md catalog index is stale" >&2
                echo "Regenerate with: ./scripts/regen-skillcards.sh" >&2
                exit 1
            fi
        else
            echo "regen-skillcards.sh: SKILLCARDS.md catalog is missing" >&2
            echo "Regenerate with: ./scripts/regen-skillcards.sh" >&2
            exit 1
        fi
        echo "regen-skillcards.sh: all SKILLCARD.yaml manifests + SKILLCARDS.md catalog are fresh"
        ;;
    write)
        emit_catalog_index > SKILLCARDS.md
        echo "regen-skillcards.sh: wrote $written SKILLCARD.yaml + SKILLCARDS.md catalog ($skipped ServiceLoader entries skipped — not pinned in snapshot)"
        ;;
esac
