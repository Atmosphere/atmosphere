#!/bin/sh
# Atmosphere CLI Installer
# Usage: curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh
#
# Copyright 2008-2026 Async-IO.org — Apache License 2.0

set -e

VERSION="4.0.39"
INSTALL_DIR="${ATMOSPHERE_INSTALL_DIR:-/usr/local/bin}"
ATMOSPHERE_HOME="${ATMOSPHERE_HOME:-$HOME/.atmosphere}"
BASE_URL="https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli"

# ── Colors ──────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    BOLD='\033[1m'
    CYAN='\033[36m'
    GREEN='\033[32m'
    YELLOW='\033[33m'
    RED='\033[31m'
    RESET='\033[0m'
else
    BOLD='' CYAN='' GREEN='' YELLOW='' RED='' RESET=''
fi

info()  { printf "${CYAN}→${RESET} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${RESET} %s\n" "$1"; }
warn()  { printf "${YELLOW}!${RESET} %s\n" "$1"; }
die()   { printf "${RED}error:${RESET} %s\n" "$1" >&2; exit 1; }

# ── Detect platform ────────────────────────────────────────────────────────
detect_platform() {
    os=$(uname -s | tr '[:upper:]' '[:lower:]')
    case "$os" in
        darwin|linux) ;;
        *) die "Unsupported OS: $os. Atmosphere CLI supports macOS and Linux." ;;
    esac
}

# ── Check prerequisites ────────────────────────────────────────────────────
check_java() {
    if command -v java >/dev/null 2>&1; then
        java_ver=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
        if [ "$java_ver" -ge 21 ] 2>/dev/null; then
            ok "Java $java_ver detected"
            return
        fi
    fi
    warn "Java 21+ not found"
    printf "\n  Install Java 21:\n"
    case "$(uname -s)" in
        Darwin) printf "    brew install openjdk@21\n" ;;
        *)      printf "    sudo apt install openjdk-21-jdk  # Debian/Ubuntu\n"
                printf "    sudo dnf install java-21-openjdk  # Fedora/RHEL\n" ;;
    esac
    printf "\n  Or use SDKMAN: https://sdkman.io\n"
    printf "    sdk install java 21-tem\n\n"
}

# ── Download ────────────────────────────────────────────────────────────────
download() {
    url="$1"; dest="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$dest" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$dest" "$url"
    else
        die "curl or wget is required"
    fi
}

# ── Install ─────────────────────────────────────────────────────────────────
install_cli() {
    printf "\n${BOLD}Atmosphere CLI Installer${RESET} v%s\n\n" "$VERSION"

    detect_platform
    check_java

    # Create home directory
    mkdir -p "$ATMOSPHERE_HOME/cache"
    ok "Created $ATMOSPHERE_HOME"

    # Download CLI script and samples.json
    info "Downloading CLI..."
    tmp_dir=$(mktemp -d)
    trap 'rm -rf "$tmp_dir"' EXIT

    download "$BASE_URL/atmosphere" "$tmp_dir/atmosphere"
    download "$BASE_URL/samples.json" "$tmp_dir/samples.json"

    chmod +x "$tmp_dir/atmosphere"

    # Install to INSTALL_DIR
    if [ -w "$INSTALL_DIR" ]; then
        cp "$tmp_dir/atmosphere" "$INSTALL_DIR/atmosphere"
        cp "$tmp_dir/samples.json" "$ATMOSPHERE_HOME/samples.json"
        ok "Installed to $INSTALL_DIR/atmosphere"
    else
        info "Installing to $INSTALL_DIR requires sudo"
        sudo cp "$tmp_dir/atmosphere" "$INSTALL_DIR/atmosphere"
        cp "$tmp_dir/samples.json" "$ATMOSPHERE_HOME/samples.json"
        ok "Installed to $INSTALL_DIR/atmosphere"
    fi

    # Also store samples.json next to the script if possible
    if [ -w "$INSTALL_DIR" ]; then
        cp "$tmp_dir/samples.json" "$INSTALL_DIR/samples.json" 2>/dev/null || true
    fi

    # Verify
    if command -v atmosphere >/dev/null 2>&1; then
        ok "Installation complete!"
    else
        warn "$INSTALL_DIR may not be in your PATH"
        printf "\n  Add to your shell profile:\n"
        printf "    export PATH=\"%s:\$PATH\"\n\n" "$INSTALL_DIR"
    fi

    # Print next steps
    printf "\n${BOLD}Get started:${RESET}\n\n"
    printf "  atmosphere list              # See all samples\n"
    printf "  atmosphere run spring-boot-chat  # Run a sample\n"
    printf "  atmosphere new my-app        # Create a new project\n"
    printf "\n"
}

# ── Uninstall ───────────────────────────────────────────────────────────────
uninstall_cli() {
    printf "\n${BOLD}Uninstalling Atmosphere CLI${RESET}\n\n"

    if [ -f "$INSTALL_DIR/atmosphere" ]; then
        if [ -w "$INSTALL_DIR" ]; then
            rm -f "$INSTALL_DIR/atmosphere"
            rm -f "$INSTALL_DIR/samples.json"
        else
            sudo rm -f "$INSTALL_DIR/atmosphere"
            sudo rm -f "$INSTALL_DIR/samples.json"
        fi
        ok "Removed $INSTALL_DIR/atmosphere"
    fi

    if [ -d "$ATMOSPHERE_HOME" ]; then
        printf "  Remove cache? (%s) [y/N] " "$ATMOSPHERE_HOME"
        read -r confirm
        if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
            rm -rf "$ATMOSPHERE_HOME"
            ok "Removed $ATMOSPHERE_HOME"
        fi
    fi

    ok "Uninstall complete"
    printf "\n"
}

# ── Main ────────────────────────────────────────────────────────────────────
case "${1:-install}" in
    install)   install_cli ;;
    uninstall) uninstall_cli ;;
    *)         die "Usage: install.sh [install|uninstall]" ;;
esac
