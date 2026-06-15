#!/usr/bin/env bash
#
# Catch link rot in external URLs referenced from the docs of BOTH repos
# (main atmosphere *.md + sibling atmosphere.github.io *.astro/*.md/*.mdx).
# Drift-log #19: the A2A spec URL https://google.github.io/A2A/ 404'd after
# the project moved to the Linux Foundation, and nothing flagged it.
#
# For each unique external URL:
#   - HTTP >= 400 (after following redirects)  → DEAD LINK, hard failure.
#   - connection error / timeout (curl code 000) → unreachable; reported as a
#     warning, NOT a failure (could be transient, a firewall, or a site that
#     blocks HEAD/bots).
#   - 2xx/3xx → OK.
# Probes run in parallel (xargs -P) so the full corpus finishes in well under
# a minute on a normal connection. If a quick network precheck fails, the whole
# run is SKIPPED (exit 0): this is a link-rot check, not a connectivity check,
# so an offline box must not turn it red.
#
# This is NOT wired into pre-push (network calls do not belong on the push
# hot-path). It is meant for: the doc-drift auditor, a scheduled CI docs lane,
# or manual `./scripts/check-website-urls.sh`.
#
# Honours .harness/url-allowlist.txt (substring match) for URLs that are known
# to block automated probes or are intentionally non-resolving examples.
#
# Run from repo root. Exits 0 on success / skip, 1 on any dead link.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ALLOWLIST=".harness/url-allowlist.txt"
PARALLELISM="${LINKCHECK_PARALLELISM:-10}"
export UA="Mozilla/5.0 (compatible; atmosphere-linkcheck/1.0)"
export CONNECT_TIMEOUT=5
export MAX_TIME=12

# Locate the sibling site (optional).
SITE_DIR="${ATMOSPHERE_SITE_DIR:-}"
if [ -z "$SITE_DIR" ]; then
    for cand in "../atmosphere.github.io" "$HOME/workspace/atmosphere/atmosphere.github.io"; do
        if [ -d "$cand" ]; then SITE_DIR="$cand"; break; fi
    done
fi

# ── Network precheck: if a stable control URL is unreachable, skip. ──
if ! curl -sS -o /dev/null --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
        -A "$UA" "https://example.com" 2>/dev/null; then
    echo "check-website-urls.sh: network precheck failed (example.com unreachable) — SKIPPING link check"
    exit 0
fi

# ── Collect candidate files. Exclude append-only history (drift-log, CHANGELOG,
#    the site's whats-new) — it legitimately quotes superseded / now-dead URLs. ──
mapfile -t FILES < <(git ls-files '*.md' | grep -vE '(^|/)(CHANGELOG\.md|drift-log\.md)$')
if [ -n "$SITE_DIR" ] && [ -d "$SITE_DIR" ]; then
    echo "check-website-urls.sh: including sibling site at $SITE_DIR"
    while IFS= read -r f; do FILES+=("$f"); done < <(
        find "$SITE_DIR" \( -name '*.astro' -o -name '*.md' -o -name '*.mdx' \) \
            -not -name 'whats-new.md' \
            -not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/dist/*' \
            -not -path '*/.astro/*' -not -path '*/build/*' 2>/dev/null
    )
fi

# ── Extract + normalise URLs. Grab up to whitespace, then strip trailing
#    Markdown/markup punctuation in Python (robust against ")**", "):", "].",
#    backticks, quotes — the noise that made a bare regex report false 404s). ──
extract_urls() {
    grep -rhoE 'https?://[^[:space:]]+' "${FILES[@]}" 2>/dev/null \
        | python3 -c '
import sys
TRAIL = ".,;:!*\")]}>`'"'"'"
SKIP = ("<", ">", "...", "$", "{", "}")
for raw in sys.stdin:
    # Split Markdown image/link concatenations like IMG)](LINK into two tokens.
    for part in raw.strip().replace(")](", " ").replace("](", " ").split():
        if any(s in part for s in SKIP):   # template/elided placeholder, not a real link
            continue
        u = part.lstrip("([").rstrip(TRAIL)
        if u.startswith("http"):
            print(u)' \
        | sort -u
}

# ── Intrinsic skips + allowlist (filter before probing). ──
mapfile -t ALLOW < <(
    [ -f "$ALLOWLIST" ] && sed -E 's/#.*$//' "$ALLOWLIST" | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' | grep -v '^$' || true
)
is_skipped() {
    local url="$1"
    case "$url" in
        *localhost*|*127.0.0.1*|*example.com*|*example.org*|*w3.org/2000/svg*)
            return 0 ;;
        *'<'*|*'>'*|*'{'*|*'}'*|*'$'*)  # template placeholders, not real links
            return 0 ;;
    esac
    local entry
    for entry in "${ALLOW[@]:-}"; do
        [ -z "$entry" ] && continue
        case "$url" in *"$entry"*) return 0 ;; esac
    done
    return 1
}

TMP_URLS="$(mktemp)"
trap 'rm -f "$TMP_URLS"' EXIT
skipped=0
while IFS= read -r url; do
    [ -z "$url" ] && continue
    if is_skipped "$url"; then skipped=$((skipped+1)); continue; fi
    echo "$url"
done < <(extract_urls) > "$TMP_URLS"

# ── Probe one URL → prints "<code>\t<url>". HEAD first, GET fallback. ──
probe_one() {
    local url="$1" code
    code=$(curl -sS -o /dev/null -L --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
        --retry 1 --retry-delay 1 -A "$UA" -w '%{http_code}' -I "$url" 2>/dev/null || echo 000)
    case "$code" in 000|403|405|501)
        code=$(curl -sS -o /dev/null -L --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
            --retry 1 --retry-delay 1 -A "$UA" -w '%{http_code}' "$url" 2>/dev/null || echo 000) ;;
    esac
    printf '%s\t%s\n' "$code" "$url"
}
export -f probe_one

total=$(wc -l < "$TMP_URLS" | tr -d ' ')
echo "check-website-urls.sh: probing $total unique URLs ($skipped skipped) with parallelism $PARALLELISM"
# NUL-delimit + -n1 (one URL per invocation, URL becomes $0) so quotes/specials
# and very long URLs don't break xargs assembly. -I{} is deliberately avoided:
# it batches the whole replacement and aborts on an over-long line.
RESULTS="$(tr '\n' '\0' < "$TMP_URLS" | xargs -0 -P "$PARALLELISM" -n 1 bash -c 'probe_one "$0"')"

# ── Classify. 401/403/429 = blocked (auth/bot-wall/rate-limit), advisory not
#    dead. 000 = unreachable, advisory. 400/404/410/5xx = real dead link. ──
dead=0 unreachable=0 blocked=0 ok=0
DEAD_LINKS="" UNREACHABLE_LINKS="" BLOCKED_LINKS=""
while IFS=$'\t' read -r code url; do
    [ -z "$code" ] && continue
    if [ "$code" = "000" ]; then
        unreachable=$((unreachable+1)); UNREACHABLE_LINKS+="  $url"$'\n'
    elif [ "$code" = "401" ] || [ "$code" = "403" ] || [ "$code" = "405" ] || [ "$code" = "429" ]; then
        # 405 = method not allowed → the endpoint EXISTS (POST-only API etc.); alive.
        blocked=$((blocked+1)); BLOCKED_LINKS+="  $code $url"$'\n'
    elif [ "$code" -ge 400 ] 2>/dev/null; then
        dead=$((dead+1)); DEAD_LINKS+="  $code $url"$'\n'
    else
        ok=$((ok+1))
    fi
done <<< "$RESULTS"

echo ""
echo "check-website-urls.sh: $ok OK, $dead dead, $blocked blocked, $unreachable unreachable, $skipped skipped"

# Guard against a silent false-green: if we had URLs to probe but classified
# none, the probe harness failed — do NOT report "OK".
probed=$((ok+dead+blocked+unreachable))
if [ "$total" -gt 0 ] && [ "$probed" -eq 0 ]; then
    echo "check-website-urls.sh: ERROR — probed 0 of $total URLs (harness failure, not a clean result)" >&2
    exit 2
fi

if [ "$blocked" -gt 0 ]; then
    echo "" >&2
    echo "Blocked (advisory — 401/403/429 auth wall / bot block / rate limit; allowlist if persistent):" >&2
    printf '%s' "$BLOCKED_LINKS" >&2
fi

if [ "$unreachable" -gt 0 ]; then
    echo "" >&2
    echo "Unreachable (advisory — transient/firewall/timeout; allowlist if persistent):" >&2
    printf '%s' "$UNREACHABLE_LINKS" >&2
fi

if [ "$dead" -gt 0 ]; then
    echo "" >&2
    echo "DEAD LINKS (HTTP >= 400) — fix the reference or allowlist in $ALLOWLIST:" >&2
    printf '%s' "$DEAD_LINKS" >&2
    exit 1
fi

echo "check-website-urls.sh: OK (no dead external links)"
