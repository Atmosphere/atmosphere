#!/usr/bin/env bash
#
# Sign every modules/<X>/SKILLCARD.yaml with the OpenSSF Model Signing
# (OMS) toolchain, emitting modules/<X>/SKILLCARD.yaml.sig — a sigstore
# bundle (DSSE envelope wrapping an in-toto statement of the card's
# digest). The sig format is exactly what NVIDIA's verified-skills
# programme uses; see https://developer.nvidia.com/blog/nvidia-verified-agent-skills-provide-capability-governance-for-ai-agents/
# and https://github.com/sigstore/model-transparency.
#
# Modes (PKI methods, in order of trust):
#   sigstore   — keyless OIDC via Fulcio + Rekor (default; production
#                CI signing path). Requires `id-token: write` in the
#                GitHub Actions workflow OR an ambient OIDC token. The
#                resulting bundle includes a short-lived cert chain
#                from Fulcio + a Rekor transparency-log inclusion proof,
#                which is the industry-standard "verifiable by anyone"
#                trust anchor.
#   key        — long-lived private-key signing (developer proof /
#                local-only). The pubkey must be distributed
#                out-of-band for verification to mean anything; this
#                mode exists so you can prove the round-trip locally
#                without needing an OIDC identity. NOT FOR PRODUCTION.
#   certificate— x509 cert chain signing (regulated environments).
#                NVIDIA's blog uses this mode with nv-agent-root-cert.pem.
#                Requires --certificate + --private_key.
#
# Usage:
#   ./scripts/sign-skillcards.sh                    # sigstore mode (CI)
#   ./scripts/sign-skillcards.sh --key PATH         # private-key mode
#   ./scripts/sign-skillcards.sh --certificate PATH \
#                                --private_key PATH # cert mode
#   ./scripts/sign-skillcards.sh --check            # exit 1 if any card
#                                                   # lacks a .sig
#
# The model_signing CLI must be on PATH (`pip install model-signing`).

set -euo pipefail
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MODE="sigstore"
KEY_PATH=""
CERT_PATH=""
CHECK=false

while [ $# -gt 0 ]; do
    case "$1" in
        --key)          MODE="key"; KEY_PATH="$2"; shift 2 ;;
        --certificate)  MODE="certificate"; CERT_PATH="$2"; shift 2 ;;
        --private_key)  KEY_PATH="$2"; shift 2 ;;
        --check)        CHECK=true; shift ;;
        --sigstore)     MODE="sigstore"; shift ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "sign-skillcards.sh: unknown arg $1" >&2; exit 2 ;;
    esac
done

if ! python3 -m model_signing --version >/dev/null 2>&1; then
    echo "sign-skillcards.sh: model_signing CLI not available (pip install model-signing)" >&2
    exit 1
fi

mapfile -t CARDS < <(find modules -maxdepth 2 -name SKILLCARD.yaml -not -path '*/target/*' | sort)
if [ ${#CARDS[@]} -eq 0 ]; then
    echo "sign-skillcards.sh: no SKILLCARD.yaml manifests found under modules/" >&2
    exit 1
fi

if [ "$CHECK" = true ]; then
    missing=()
    for card in "${CARDS[@]}"; do
        if [ ! -f "${card}.sig" ]; then
            missing+=("$card.sig")
        fi
    done
    if [ ${#missing[@]} -gt 0 ]; then
        echo "sign-skillcards.sh: ${#missing[@]} unsigned card(s):" >&2
        for f in "${missing[@]}"; do echo "  $f" >&2; done
        echo "" >&2
        echo "Re-run: ./scripts/sign-skillcards.sh (sigstore mode requires id-token:write in CI)" >&2
        exit 1
    fi
    echo "sign-skillcards.sh: all ${#CARDS[@]} SKILLCARD.yaml.sig present"
    exit 0
fi

signed=0
for card in "${CARDS[@]}"; do
    sig="${card}.sig"
    case "$MODE" in
        sigstore)
            # model_signing's sigstore plugin will try an interactive
            # OIDC flow unless given a pre-fetched identity token. CI
            # workflows fetch the GitHub OIDC token explicitly and pass
            # it via SIGSTORE_IDENTITY_TOKEN env var so this script
            # works headless. Local dev without SIGSTORE_IDENTITY_TOKEN
            # falls back to the interactive flow (browser pop-up).
            sigstore_args=(--signature "$sig")
            if [ -n "${SIGSTORE_IDENTITY_TOKEN:-}" ]; then
                sigstore_args+=(--identity_token "$SIGSTORE_IDENTITY_TOKEN")
            fi
            python3 -m model_signing sign sigstore \
                "${sigstore_args[@]}" \
                "$card" >/dev/null
            ;;
        key)
            if [ -z "$KEY_PATH" ]; then
                echo "sign-skillcards.sh: --key requires a PEM path" >&2
                exit 2
            fi
            python3 -m model_signing sign key \
                --private_key "$KEY_PATH" \
                --signature "$sig" "$card" >/dev/null
            ;;
        certificate)
            if [ -z "$CERT_PATH" ] || [ -z "$KEY_PATH" ]; then
                echo "sign-skillcards.sh: --certificate requires both --certificate and --private_key" >&2
                exit 2
            fi
            python3 -m model_signing sign certificate \
                --certificate "$CERT_PATH" \
                --private_key "$KEY_PATH" \
                --signature "$sig" "$card" >/dev/null
            ;;
        *)
            echo "sign-skillcards.sh: unknown mode $MODE" >&2
            exit 2
            ;;
    esac
    signed=$((signed + 1))
done

echo "sign-skillcards.sh: signed $signed SKILLCARD.yaml manifests in $MODE mode"
