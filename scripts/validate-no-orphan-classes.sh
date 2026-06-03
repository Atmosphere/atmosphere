#!/usr/bin/env bash
#
# Catch NEWLY-ADDED concrete production classes that ship with no consumer —
# the "born-dead / born-redundant" pattern.
#
# Why this exists (release-readiness audit, 2026-06-02/03): ten dead/unwired
# concrete classes were found and removed (McpWebSocketHandler, AgUiSession,
# AiCoalescingBroadcasterCache, AdkArtifactBridge, AdkCompactionBridge,
# AtmosphereRequestBridge, AtmosphereResponseBridge, AuditLoggingFilter,
# GrpcProtocolBridge, ListTaskPushNotificationConfigsResponse). Git history
# showed every one was introduced inside a large multi-artifact `feat` commit
# beside a sibling that got wired — but its own consumer/registration was never
# landed. The existing architectural-validation gate catches unwired NOOP
# *constants*, dead *interfaces* (zero impls), and empty SPI files — but NOT a
# concrete class with zero production callers.
#
# DESIGN — diff-aware on purpose. A *full-tree* "zero internal consumers" scan
# over-flags on a published library: public-API extension points
# (e.g. WebSocketHandlerAdapter, EchoProtocol, the pgvector/qdrant/pinecone
# ContextProviders) legitimately have no in-repo caller — users wire them. So
# this gate only inspects classes ADDED in the current changeset (vs BASE_REF):
# a brand-new concrete class with no consumer in the same push is the precise
# "you added it but forgot to wire it" signal, and pre-existing public API is
# never touched. Use --all for a full-tree advisory sweep (will list public-API
# classes — triage by hand, do not blindly fail on it).
#
# A class is flagged ONLY if ALL hold:
#   * concrete top-level `public class`/`record` under modules/*/src/main/java
#     (not interface, @interface, enum, or abstract),
#   * no framework stereotype annotation (Spring/Quarkus/JAX-RS/Atmosphere),
#   * FQN not in any META-INF/services, spring.factories, or *.imports,
#   * no `public static void main`,
#   * simple name appears in NO production source (modules|samples src/main,
#     non-.md) outside its own file — test-only/doc-only refs are not a consumer,
#   * not in .harness/orphan-class-allowlist.txt.
#
# Run from repo root. Exits 0 when clean, 1 otherwise.
# Java only in v1 (Kotlin candidate-detection is a documented follow-up).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/orphan-class-allowlist.txt"
BASE_REF="${BASE_REF:-origin/main}"
MODE="diff"
[ "${1:-}" = "--all" ] && MODE="all"

STEREOTYPES='@(Component|Service|Repository|Configuration|RestController|Controller|ControllerAdvice|AutoConfiguration|AutoConfigureBefore|AutoConfigureAfter|ApplicationScoped|RequestScoped|SessionScoped|Dependent|Singleton|Recorder|Path|Provider|Agent|Coordinator|AiEndpoint|WebServlet|WebFilter|WebListener|ServerEndpoint|Mojo|SpringBootApplication|Mapper|Entity|Embeddable|MappedSuperclass|ConfigMapping|ConfigRoot|BuildStep|Extension|AutoService|Component\()'

REGISTERED="$(mktemp)"
trap 'rm -f "$REGISTERED"' EXIT
find modules -path '*/src/main/resources/META-INF/services/*' -type f 2>/dev/null \
    -exec cat {} + 2>/dev/null | sed 's/#.*//; s/[[:space:]]//g' | grep -E '^[a-zA-Z0-9_.$]+$' >> "$REGISTERED" || true
find modules -path '*/src/main/resources/META-INF/*' \( -name 'spring.factories' -o -name '*.imports' \) -type f 2>/dev/null \
    -exec cat {} + 2>/dev/null | tr ',\\\\' '\n' | sed 's/#.*//; s/[[:space:]]//g' | grep -E '^[a-zA-Z0-9_.$]+$' >> "$REGISTERED" || true

is_registered() { grep -qxF "$1" "$REGISTERED"; }
is_allowlisted() {
    [ -f "$ALLOWLIST" ] || return 1
    grep -vE '^\s*#' "$ALLOWLIST" 2>/dev/null | sed 's/[[:space:]]//g' | grep -qxF "$1"
}

# ---- Select the candidate file set. ----------------------------------------
if [ "$MODE" = "all" ]; then
    echo "validate-no-orphan-classes.sh: full-tree advisory sweep (--all)"
    mapfile -t FILES < <(find modules -path '*/src/main/java/*' -name '*.java' -type f 2>/dev/null | sort)
else
    if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
        echo "validate-no-orphan-classes.sh: BASE_REF '$BASE_REF' not found — skipping (diff mode needs a base)."
        exit 0
    fi
    MB="$(git merge-base "$BASE_REF" HEAD 2>/dev/null || echo "$BASE_REF")"
    mapfile -t FILES < <(git diff --diff-filter=AR --name-only "$MB"...HEAD -- 'modules/*/src/main/java/**/*.java' 2>/dev/null | sort)
    echo "validate-no-orphan-classes.sh: diff mode vs ${BASE_REF} — ${#FILES[@]} added/renamed production class file(s)"
fi

ORPHANS=""
ORPHAN_COUNT=0
for f in "${FILES[@]}"; do
    [ -n "$f" ] && [ -f "$f" ] || continue
    # Test-support / benchmark modules ship src/main classes that are consumed
    # by OTHER modules' tests or by a JMH/integration harness by design (e.g.
    # ai-test's AbstractAgentRuntimeContractTest / HttpRuntimeTestSupport,
    # integration-tests fixtures, benchmarks' JMH classes). Their lack of a
    # production consumer is expected, so they are out of scope for this gate.
    case "$f" in
        modules/ai-test/*|modules/integration-tests/*|modules/benchmarks/*) continue;;
    esac
    name="$(basename "$f" .java)"
    case "$name" in package-info|module-info) continue;; esac

    grep -qE "^[[:space:]]*public[[:space:]]+(final[[:space:]]+|sealed[[:space:]]+|non-sealed[[:space:]]+)*(class|record)[[:space:]]+${name}\b" "$f" || continue
    grep -qE "$STEREOTYPES" "$f" && continue
    grep -qE 'public[[:space:]]+static[[:space:]]+void[[:space:]]+main[[:space:]]*\(' "$f" && continue

    pkg="$(grep -m1 -E '^[[:space:]]*package[[:space:]]' "$f" | sed -E 's/^[[:space:]]*package[[:space:]]+//; s/[[:space:]]*;.*//')"
    fqn="${pkg:+$pkg.}$name"
    is_registered "$fqn" && continue
    is_allowlisted "$name" && continue
    is_allowlisted "$fqn" && continue

    refs="$(git grep -lw "$name" -- 'modules' 'samples' 2>/dev/null \
        | grep -E '/src/main/' | grep -vE '\.md$' | grep -vxF "$f" | head -1 || true)"
    [ -n "$refs" ] && continue

    ORPHANS="${ORPHANS}  ${fqn}\n      (${f})\n"
    ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
done

if [ "$ORPHAN_COUNT" -gt 0 ]; then
    echo "validate-no-orphan-classes.sh: FAIL — ${ORPHAN_COUNT} new concrete class(es) shipped with no production consumer:" >&2
    echo -e "$ORPHANS" >&2
    echo "Each is a newly-added class with zero production callers and no" >&2
    echo "ServiceLoader/Spring/Quarkus/annotation wiring. Wire it to a real" >&2
    echo "consumer, delete it, or — if it is a genuine public-API entry point —" >&2
    echo "add its name or FQN to ${ALLOWLIST} with a one-line justification." >&2
    exit 1
fi

echo "validate-no-orphan-classes.sh: OK (no newly-added orphan production classes)"
