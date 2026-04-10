#!/bin/sh
# SDKMAN vendor API publisher for Atmosphere CLI.
# Usage: ./cli/sdkman/publish.sh <version>       e.g. 4.0.35
#
# Required environment:
#   SDKMAN_CONSUMER_KEY    — issued by SDKMAN team after GPG onboarding
#   SDKMAN_CONSUMER_TOKEN  — issued by SDKMAN team after GPG onboarding
#
# Optional:
#   SDKMAN_SET_DEFAULT=false  # skip setting the release as default (default: true)
#   SDKMAN_ANNOUNCE=false     # skip broadcast announcement (default: true)

set -eu

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>    e.g. $0 4.0.35" >&2
    exit 1
fi

: "${SDKMAN_CONSUMER_KEY:?missing SDKMAN_CONSUMER_KEY}"
: "${SDKMAN_CONSUMER_TOKEN:?missing SDKMAN_CONSUMER_TOKEN}"

CANDIDATE="atmosphere"
ARCHIVE_URL="https://github.com/Atmosphere/atmosphere/releases/download/atmosphere-${VERSION}/atmosphere-${VERSION}.zip"
VENDOR_API="https://vendors.sdkman.io"

# Verify the archive exists and is reachable
http_code=$(curl -sL -o /dev/null -w "%{http_code}" -I "$ARCHIVE_URL")
if [ "$http_code" != "200" ]; then
    echo "error: archive not reachable at $ARCHIVE_URL (HTTP $http_code)" >&2
    echo "       did the release workflow finish and attach the SDKMAN zip?" >&2
    exit 1
fi
echo "✓ Archive reachable: $ARCHIVE_URL"

# Step 1 — POST /release (idempotent)
echo ""
echo "→ Releasing $CANDIDATE $VERSION to SDKMAN..."
curl -sf -X POST \
    -H "Consumer-Key: $SDKMAN_CONSUMER_KEY" \
    -H "Consumer-Token: $SDKMAN_CONSUMER_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"candidate\": \"$CANDIDATE\", \"version\": \"$VERSION\", \"url\": \"$ARCHIVE_URL\"}" \
    "$VENDOR_API/release" || {
    echo "error: POST /release failed" >&2
    exit 1
}
echo "✓ Released"

# Step 2 — PUT /default (set as default)
if [ "${SDKMAN_SET_DEFAULT:-true}" = "true" ]; then
    echo ""
    echo "→ Setting $VERSION as default..."
    curl -sf -X PUT \
        -H "Consumer-Key: $SDKMAN_CONSUMER_KEY" \
        -H "Consumer-Token: $SDKMAN_CONSUMER_TOKEN" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json" \
        -d "{\"candidate\": \"$CANDIDATE\", \"version\": \"$VERSION\"}" \
        "$VENDOR_API/default" || {
        echo "error: PUT /default failed" >&2
        exit 1
    }
    echo "✓ Default set"
fi

# Step 3 — POST /announce/struct (broadcast)
if [ "${SDKMAN_ANNOUNCE:-true}" = "true" ]; then
    echo ""
    echo "→ Announcing release..."
    RELEASE_URL="https://github.com/Atmosphere/atmosphere/releases/tag/atmosphere-${VERSION}"
    curl -sf -X POST \
        -H "Consumer-Key: $SDKMAN_CONSUMER_KEY" \
        -H "Consumer-Token: $SDKMAN_CONSUMER_TOKEN" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json" \
        -d "{\"candidate\": \"$CANDIDATE\", \"version\": \"$VERSION\", \"url\": \"$RELEASE_URL\"}" \
        "$VENDOR_API/announce/struct" || {
        echo "error: POST /announce/struct failed" >&2
        exit 1
    }
    echo "✓ Announced"
fi

echo ""
echo "Done. Users can now install with:"
echo "  sdk install $CANDIDATE $VERSION"
