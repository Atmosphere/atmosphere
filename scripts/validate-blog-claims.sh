#!/usr/bin/env bash
# validate-blog-claims.sh — claims-vs-released-artifact gate.
#
# Validates every claim in .harness/blog-claims.json against a git ref
# (default: the latest atmosphere-4.* tag) and against Maven Central:
#
#   1. Evidence half: for each { path, symbol } pair, `git grep` the symbol
#      at the REF restricted to that path, EXCLUDING comment/Javadoc-only
#      hits — lines whose trimmed content starts with `*`, `//`, `/*`,
#      `import `, or `<!--` are stripped BEFORE matching. This repo was
#      burned by a self-satisfying gate that counted its own citations
#      (EvidenceConsumerGrepPinTest); evidence paths under .harness/ are
#      rejected outright for the same reason.
#   2. Artifact half: for each "artifacts" GAV, HTTP-check the Maven
#      Central pom at the version derived from the tag name
#      (atmosphere-4.0.60 -> 4.0.60) and require HTTP 200.
#
# WHY THIS EXISTS: the Atmosphere-4 blog was audited claim-by-claim against
# MAIN and passed, while the released artifact (4.0.59) lacked ~10
# load-bearing claims — worst, the blog's step-one dependency
# atmosphere-ai-spring-boot-starter had NEVER existed on Maven Central, so
# a reader failed at step one. Public claims must only flip to "published"
# after this gate passes against the RELEASED artifact.
#
# PROMOTION RULE (release checklist): the blog post must NOT be promoted /
# published until this script exits 0 against the cut release tag —
# `./scripts/validate-blog-claims.sh atmosphere-<version>` — i.e. both the
# tagged tree AND Maven Central agree with every claim.
#
# Usage:
#   ./scripts/validate-blog-claims.sh                       # latest atmosphere-4.* tag, tree + Central
#   ./scripts/validate-blog-claims.sh atmosphere-4.0.59     # explicit tag, tree + Central
#   ./scripts/validate-blog-claims.sh --against-main        # HEAD tree only, Central skipped (pre-release)
#   ./scripts/validate-blog-claims.sh REF --skip-central    # explicit ref, Central skipped
#   ./scripts/validate-blog-claims.sh --artifacts-only 4.0.60  # Central existence half only (post-publish)
#   ./scripts/validate-blog-claims.sh --manifest FILE ...   # alternate manifest
#
# Environment:
#   CENTRAL_ATTEMPTS (default 3)  — HTTP attempts per artifact
#   CENTRAL_DELAY    (default 5)  — seconds between attempts
#
# COVERAGE BOUNDS (no silent caps — what this gate does NOT check):
#   - Only claims listed in the manifest are validated; blog prose not in
#     the manifest is out of scope (the doc-drift-audit covers semantics).
#   - Symbol presence proves the code shipped, not that it behaves; the
#     behavior half lives in the module test suites.
#   - Comment stripping is line-based: a block-comment interior line that
#     does not start with `*` (non-idiomatic in this codebase, and
#     checkstyle-enforced Javadoc style makes it rare) would evade the
#     filter.
#   - Central checks cover the pom's existence, not jar contents.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

MANIFEST=".harness/blog-claims.json"
REF=""
SKIP_CENTRAL=0
ARTIFACTS_ONLY=0
ARTIFACTS_VERSION=""
CENTRAL_ATTEMPTS="${CENTRAL_ATTEMPTS:-3}"
CENTRAL_DELAY="${CENTRAL_DELAY:-5}"

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --against-main) REF="HEAD"; SKIP_CENTRAL=1 ;;
    --skip-central) SKIP_CENTRAL=1 ;;
    --artifacts-only)
      ARTIFACTS_ONLY=1
      ARTIFACTS_VERSION="${2:?--artifacts-only requires a version}"; shift ;;
    --manifest)
      MANIFEST="${2:?--manifest requires a file}"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "ERROR: unknown flag: $1" >&2; exit 2 ;;
    *) REF="$1" ;;
  esac
  shift
done

[ -f "$MANIFEST" ] || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 2; }

if [ "$ARTIFACTS_ONLY" -eq 0 ] && [ -z "$REF" ]; then
  REF="$(git tag -l 'atmosphere-4.*' --sort=-v:refname | head -1)"
  [ -n "$REF" ] || { echo "ERROR: no atmosphere-4.* tag found and no REF given" >&2; exit 2; }
fi

# Derive the Central version from the ref name (atmosphere-X.Y.Z -> X.Y.Z).
VERSION=""
if [ "$ARTIFACTS_ONLY" -eq 1 ]; then
  VERSION="$ARTIFACTS_VERSION"
elif [ "$SKIP_CENTRAL" -eq 0 ]; then
  case "$REF" in
    atmosphere-*) VERSION="${REF#atmosphere-}" ;;
    *)
      echo "ERROR: cannot derive a Maven Central version from ref '$REF'." >&2
      echo "       Pass a release tag (atmosphere-X.Y.Z), or use --skip-central / --against-main." >&2
      exit 2 ;;
  esac
fi

# Flatten the manifest to tab-separated records:  C id claim | E id path symbol | A id gav
records="$(python3 - "$MANIFEST" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    doc = json.load(f)
for c in doc["claims"]:
    cid, claim = c["id"], c["claim"]
    for field in (cid, claim):
        if "\t" in field or "\n" in field:
            raise SystemExit(f"manifest field contains tab/newline: {field!r}")
    print(f"C\t{cid}\t{claim}")
    for ev in c.get("evidence", []):
        print(f"E\t{cid}\t{ev['path']}\t{ev['symbol']}")
    for gav in c.get("artifacts", []):
        print(f"A\t{cid}\t{gav}")
PY
)"

# Strip comment/Javadoc/import lines so a citation-only mention can never
# satisfy an evidence check (the self-satisfying-gate class of bug).
strip_comments() {
  awk '{
    s = $0
    sub(/^[[:space:]]+/, "", s)
    if (s ~ /^\*/)      next
    if (s ~ /^\/\//)    next
    if (s ~ /^\/\*/)    next
    if (s ~ /^import /) next
    if (s ~ /^<!--/)    next
    print
  }'
}

check_evidence() { # $1=path $2=symbol -> prints failure reason, returns 1 on failure
  local path="$1" symbol="$2"
  case "$path" in
    .harness/*)
      echo "evidence path '$path' is inside .harness/ — a manifest may not cite itself"
      return 1 ;;
  esac
  if ! git cat-file -e "$REF:$path" 2>/dev/null; then
    echo "file missing at $REF: $path"
    return 1
  fi
  local hits
  hits="$(git grep -hF -e "$symbol" "$REF" -- "$path" 2>/dev/null | strip_comments | grep -cF -e "$symbol" || true)"
  if [ "${hits:-0}" -eq 0 ]; then
    echo "symbol not found in non-comment code at $REF: $path :: $symbol"
    return 1
  fi
  return 0
}

check_artifact() { # $1=gav -> prints failure reason, returns 1 on failure
  local gav="$1" group artifact url code attempt
  group="${gav%%:*}"
  artifact="${gav#*:}"
  url="https://repo1.maven.org/maven2/${group//./\/}/${artifact}/${VERSION}/${artifact}-${VERSION}.pom"
  code="000"
  for attempt in $(seq 1 "$CENTRAL_ATTEMPTS"); do
    code="$(curl -sI -o /dev/null -w '%{http_code}' --max-time 20 "$url" || echo "000")"
    [ "$code" = "200" ] && return 0
    [ "$attempt" -lt "$CENTRAL_ATTEMPTS" ] && sleep "$CENTRAL_DELAY"
  done
  echo "artifact not on Maven Central (HTTP $code): $gav:$VERSION ($url)"
  return 1
}

echo "== validate-blog-claims =="
echo "   manifest : $MANIFEST"
if [ "$ARTIFACTS_ONLY" -eq 1 ]; then
  echo "   mode     : artifacts-only (Central existence @ $VERSION)"
else
  echo "   ref      : $REF"
  if [ "$SKIP_CENTRAL" -eq 1 ]; then
    echo "   central  : SKIPPED (pre-release mode) — artifact GAVs are NOT verified in this run"
  else
    echo "   central  : version $VERSION"
  fi
fi
echo

total=0
failed=0
skipped=0
current_id=""
current_claim=""
current_failures=""
current_checked=0

flush_claim() {
  [ -n "$current_id" ] || return 0
  if [ "$current_checked" -eq 0 ]; then
    # Nothing ran for this claim (artifacts-only mode + no artifact GAVs).
    # Surface it as SKIP, never as a vacuous PASS.
    skipped=$((skipped + 1))
    echo "[SKIP] $current_id — $current_claim (no artifact GAVs; evidence half not run in this mode)"
    return 0
  fi
  total=$((total + 1))
  if [ -z "$current_failures" ]; then
    echo "[PASS] $current_id — $current_claim"
  else
    failed=$((failed + 1))
    echo "[FAIL] $current_id — $current_claim"
    printf '%s' "$current_failures"
  fi
}

while IFS=$'\t' read -r kind cid f1 f2; do
  case "$kind" in
    C)
      flush_claim
      current_id="$cid"
      current_claim="$f1"
      current_failures=""
      current_checked=0
      ;;
    E)
      [ "$ARTIFACTS_ONLY" -eq 1 ] && continue
      current_checked=$((current_checked + 1))
      if ! reason="$(check_evidence "$f1" "$f2")"; then
        current_failures="${current_failures}       - ${reason}"$'\n'
      fi
      ;;
    A)
      if [ "$ARTIFACTS_ONLY" -eq 0 ] && [ "$SKIP_CENTRAL" -eq 1 ]; then
        continue
      fi
      current_checked=$((current_checked + 1))
      if ! reason="$(check_artifact "$f1")"; then
        current_failures="${current_failures}       - ${reason}"$'\n'
      fi
      ;;
  esac
done <<< "$records"
flush_claim

echo
if [ "$skipped" -gt 0 ]; then
  echo "NOTE: $skipped claim(s) skipped in this mode (listed above as SKIP)."
fi
if [ "$failed" -gt 0 ]; then
  echo "RESULT: $failed of $total checked claims FAILED against ${ARTIFACTS_VERSION:-$REF}."
  echo "Do NOT promote/publish the blog until every claim passes against the cut release tag."
  exit 1
fi
echo "RESULT: all $total checked claims verified against ${ARTIFACTS_VERSION:-$REF}."
