#!/usr/bin/env bash
#
# Verify every modules/<X>/SKILLCARD.yaml against its accompanying
# modules/<X>/SKILLCARD.yaml.sig using the OpenSSF Model Signing CLI.
# Mirrors the verify command from NVIDIA's verified-skills blog post:
#   model_signing verify <method> --signature <sig> --... <card>
#
# Modes mirror sign-skillcards.sh: sigstore (default), key, certificate.
#
# Usage:
#   ./scripts/verify-skillcards.sh                     # sigstore mode (production)
#   ./scripts/verify-skillcards.sh --key PUBKEY        # key mode (developer proof)
#   ./scripts/verify-skillcards.sh --identity EMAIL \
#                                  --identity-provider URL   # sigstore with constraints
#
# Cards without a .sig file are skipped with a warning (unsigned-card
# discovery is the job of `sign-skillcards.sh --check`, not this script).

set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MODE="sigstore"
PUBKEY_PATH=""
CERT_PATH=""
IDENTITY=""
IDENTITY_PROVIDER=""

while [ $# -gt 0 ]; do
    case "$1" in
        --key)               MODE="key"; PUBKEY_PATH="$2"; shift 2 ;;
        --public_key)        PUBKEY_PATH="$2"; shift 2 ;;
        --certificate)       MODE="certificate"; CERT_PATH="$2"; shift 2 ;;
        --certificate_chain) MODE="certificate"; CERT_PATH="$2"; shift 2 ;;
        --identity)          IDENTITY="$2"; shift 2 ;;
        --identity-provider|--identity_provider) IDENTITY_PROVIDER="$2"; shift 2 ;;
        --sigstore)          MODE="sigstore"; shift ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "verify-skillcards.sh: unknown arg $1" >&2; exit 2 ;;
    esac
done

if ! python3 -m model_signing --version >/dev/null 2>&1; then
    echo "verify-skillcards.sh: model_signing CLI not available (pip install model-signing)" >&2
    exit 1
fi

mapfile -t CARDS < <(find modules -maxdepth 2 -name SKILLCARD.yaml -not -path '*/target/*' | sort)
verified=0
skipped=0
failed=()

for card in "${CARDS[@]}"; do
    sig="${card}.sig"
    if [ ! -f "$sig" ]; then
        echo "verify-skillcards.sh: $card has no .sig — skipped"
        skipped=$((skipped + 1))
        continue
    fi
    case "$MODE" in
        sigstore)
            if [ -z "$IDENTITY" ] || [ -z "$IDENTITY_PROVIDER" ]; then
                echo "verify-skillcards.sh: sigstore mode requires --identity and --identity-provider" >&2
                exit 2
            fi
            if ! python3 -m model_signing verify sigstore \
                    --signature "$sig" \
                    --identity "$IDENTITY" \
                    --identity_provider "$IDENTITY_PROVIDER" \
                    "$card" >/dev/null 2>&1; then
                failed+=("$card")
            else
                verified=$((verified + 1))
            fi
            ;;
        key)
            if [ -z "$PUBKEY_PATH" ]; then
                echo "verify-skillcards.sh: --key requires a PEM public-key path" >&2
                exit 2
            fi
            if ! python3 -m model_signing verify key \
                    --public_key "$PUBKEY_PATH" \
                    --signature "$sig" \
                    "$card" >/dev/null 2>&1; then
                failed+=("$card")
            else
                verified=$((verified + 1))
            fi
            ;;
        certificate)
            if [ -z "$CERT_PATH" ]; then
                echo "verify-skillcards.sh: certificate mode requires --certificate_chain PATH" >&2
                exit 2
            fi
            if ! python3 -m model_signing verify certificate \
                    --certificate_chain "$CERT_PATH" \
                    --signature "$sig" \
                    "$card" >/dev/null 2>&1; then
                failed+=("$card")
            else
                verified=$((verified + 1))
            fi
            ;;
    esac
done

if [ ${#failed[@]} -gt 0 ]; then
    echo "verify-skillcards.sh: ${#failed[@]} signature verification failure(s):" >&2
    for f in "${failed[@]}"; do echo "  $f" >&2; done
    exit 1
fi

echo "verify-skillcards.sh: verified $verified, skipped $skipped (no .sig)"
