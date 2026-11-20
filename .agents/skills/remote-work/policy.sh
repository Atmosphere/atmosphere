#!/usr/bin/env bash
# ABOUTME: The remote-work policy reader, sourced by the guard hook and by remote-work.sh prepare
# ABOUTME: Classifies a command as forced, permitted, prohibited or unlisted, and measures machine pressure
#
# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright (c) 2026 dravr.ai
#
# Policy: <repo>/.claude/remote-work.json (committed), deep-merged with
# <repo>/.claude/remote-work.local.json (git-ignored, one developer's overrides). Objects merge
# key by key, and a list in the local file replaces the committed one.
#
#   forced      the guard refuses it locally; it runs only remotely
#   permitted   runs either way; with "remote_when_busy": true the guard refuses it locally
#               while the machine is past a "pressure" threshold
#   prohibited  never sent to a remote session (prepare refuses with its "why")
#   unlisted    the "unlisted" key decides: "local" (default) refuses to send, "remote" allows
#
# An entry names its command one of two ways:
#   "match":   "cargo clippy"     words at a command position: line start, after ; & | (,
#                                 behind VAR=value, env, time, nice, timeout N or a +toolchain
#   "pattern": "\\.envrc"          an extended regex matched anywhere in the command
# and may add "unless": an extended regex that, matched anywhere, cancels the entry. Atmosphere
# forces a full-reactor `./mvnw install` "unless" it names a module with -pl. An optional "name"
# is what a refusal calls the entry (a pattern is unreadable in a message).
# Quoted text is stripped first: a commit message or an echo that names a command is not that command.

# The policy as one JSON object, or nothing when the repo has no policy file.
rw_policy() {
    local base="$1/.claude/remote-work.json" local_file="$1/.claude/remote-work.local.json"
    [ -f "$base" ] || return 1
    if [ -f "$local_file" ]; then
        jq -s '.[0] * .[1]' "$base" "$local_file"
    else
        jq . "$base"
    fi
}

# The command with quoted strings removed.
rw_bare() {
    printf '%s' "$1" | perl -0777 -pe "s/'[^']*'//g; s/\"(?:[^\"\\\\]|\\\\.)*\"//g"
}

# Does the entry (JSON) match the bare command?
rw_entry_matches() {
    local entry="$1" bare="$2" words pattern re unless
    unless=$(printf '%s' "$entry" | jq -r '.unless // empty')
    if [ -n "$unless" ] && printf '%s' "$bare" | grep -Eq -- "$unless"; then
        return 1
    fi
    pattern=$(printf '%s' "$entry" | jq -r '.pattern // empty')
    if [ -n "$pattern" ]; then
        printf '%s' "$bare" | grep -Eq -- "$pattern"
        return
    fi
    words=$(printf '%s' "$entry" | jq -r '.match // empty')
    [ -n "$words" ] || return 1
    # First word, an optional +toolchain, then the remaining words, each separated by spaces.
    local first rest
    first=${words%% *}
    rest=""
    [ "$first" != "$words" ] && rest=${words#* }
    re="$(printf '%s' "$first" | sed 's/[][\.*^$+?(){}|/]/\\&/g')([[:space:]]+\\+[^[:space:]]+)?"
    if [ -n "$rest" ]; then
        local w
        for w in $rest; do
            re="${re}[[:space:]]+$(printf '%s' "$w" | sed 's/[][\.*^$+?(){}|/]/\\&/g')"
        done
    fi
    local prefix='(^|[;&|(])[[:space:]]*(([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*|env|time|nice|timeout[[:space:]]+[0-9]+[smh]?)[[:space:]]+)*'
    printf '%s' "$bare" | grep -Eq -- "${prefix}${re}([[:space:]]|\$|[;&|)])"
}

# Prints the first entry of one class that matches the bare command, or returns 1.
rw_first_match() {
    local policy="$1" class="$2" bare="$3" entry n i
    n=$(printf '%s' "$policy" | jq --arg c "$class" '(.[$c] // []) | length')
    for ((i = 0; i < n; i++)); do
        entry=$(printf '%s' "$policy" | jq -c --arg c "$class" --argjson i "$i" '.[$c][$i]')
        if rw_entry_matches "$entry" "$bare"; then
            printf '%s\n' "$entry"
            return 0
        fi
    done
    return 1
}

# Prints "<class>\t<entry json>" for the first entry that matches. Order: prohibited, forced,
# permitted, so a prohibited command can never be sent remote because a broader entry matched.
rw_classify() {
    local policy="$1" cmd="$2" bare class entry
    bare=$(rw_bare "$cmd")
    for class in prohibited forced permitted; do
        if entry=$(rw_first_match "$policy" "$class" "$bare"); then
            printf '%s\t%s\n' "$class" "$entry"
            return 0
        fi
    done
    printf 'unlisted\t{}\n'
}

# May the command be sent to a remote session? Prints "<class>\t<entry json>" when it may, and
# the refusal with a non-zero status when it may not. The remote runner types the command into
# a shell, so it must be ONE simple command: an operator, a substitution or a redirection would
# carry an unlisted or prohibited command behind a permitted one. Quoted text, which
# classification ignores, still reaches that shell, so the prohibited entries also see it.
rw_sendable() {
    local policy="$1" cmd="$2" class entry why
    case "$cmd" in
        *[\;\&\|\<\>\`\$\(\)]* | *$'\n'*)
            echo "'$cmd' is not one simple command: send each command on its own, without ; & | < > \$ \` ( ) or a newline"
            return 1 ;;
    esac
    if entry=$(rw_first_match "$policy" prohibited "$cmd"); then
        class=prohibited
    else
        IFS=$'\t' read -r class entry < <(rw_classify "$policy" "$cmd")
    fi
    why=$(printf '%s' "$entry" | jq -r '.why // empty')
    case "$class" in
        forced | permitted) ;;
        prohibited)
            echo "'$cmd' is prohibited from remote sessions${why:+: $why}"
            return 1 ;;
        *)
            if [ "$(printf '%s' "$policy" | jq -r '.unlisted // "local"')" != "remote" ]; then
                echo "'$cmd' is not in .claude/remote-work.json, and unlisted commands stay local"
                return 1
            fi ;;
    esac
    printf '%s\t%s\n' "$class" "$entry"
}

# The signed-in claude.ai account of this session: Claude Code keeps it in .claude.json, under
# CLAUDE_CONFIG_DIR when a session runs with its own config dir, else in the home directory.
rw_account() {
    local f="${CLAUDE_CONFIG_DIR:-$HOME}/.claude.json"
    [ -f "$f" ] || return 0
    jq -r '.oauthAccount.emailAddress // empty' "$f" 2>/dev/null || true
}

# rw_env <policy json>: the cloud environment a run goes to. An environment belongs to one
# claude.ai account and a config dir can switch accounts, so it is looked up from the account
# signed in now: this account's entry in
# ${REMOTE_WORK_ENVIRONMENTS:-~/.config/remote-work/environments.json} ({"<email>": "env_..."}),
# then REMOTE_WORK_ENV (for an account with no entry), then the policy's remote.environment_id.
# The map wins over the variable because a session keeps the variable it started with: after an
# account switch it names the old account's environment. Prints nothing when none applies.
rw_env() {
    local map="${REMOTE_WORK_ENVIRONMENTS:-$HOME/.config/remote-work/environments.json}" account id=""
    account=$(rw_account)
    if [ -n "$account" ] && [ -f "$map" ]; then
        id=$(jq -r --arg a "$account" '.[$a] // empty' "$map" 2>/dev/null || true)
    fi
    [ -n "$id" ] || id=${REMOTE_WORK_ENV:-}
    [ -n "$id" ] || id=$(printf '%s' "$1" | jq -r '.remote.environment_id // empty')
    [ -z "$id" ] || printf '%s\n' "$id"
}

# Machine pressure: prints the reason and returns 0 when a threshold is crossed.
# Thresholds come from the policy's "pressure" object; RW_LOAD / RW_NCPU / RW_SWAP_GB override
# the measurements (tests set them).
rw_busy() {
    local policy="$1" max_load max_swap load ncpu swap_mb
    max_load=$(printf '%s' "$policy" | jq -r '.pressure.load_per_core // empty')
    max_swap=$(printf '%s' "$policy" | jq -r '.pressure.swap_used_gb // empty')
    [ -n "$max_load$max_swap" ] || return 1
    if [ -n "${RW_LOAD:-}" ]; then load="$RW_LOAD"
    elif [ -r /proc/loadavg ]; then load=$(cut -d' ' -f1 /proc/loadavg)
    else load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}'); fi
    if [ -n "${RW_NCPU:-}" ]; then ncpu="$RW_NCPU"
    else ncpu=$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1); fi
    if [ -n "${RW_SWAP_GB:-}" ]; then swap_mb=$(awk -v g="$RW_SWAP_GB" 'BEGIN{print g*1024}')
    elif [ -r /proc/meminfo ]; then
        swap_mb=$(awk '/^SwapTotal:/{t=$2} /^SwapFree:/{f=$2} END{print (t-f)/1024}' /proc/meminfo)
    else
        swap_mb=$(sysctl -n vm.swapusage 2>/dev/null | sed -n 's/.*used = \([0-9.]*\)M.*/\1/p')
    fi
    awk -v l="${load:-0}" -v n="${ncpu:-1}" -v s="${swap_mb:-0}" -v ml="${max_load:-0}" -v ms="${max_swap:-0}" 'BEGIN {
        per = l / (n > 0 ? n : 1); gb = s / 1024; why = ""
        if (ml > 0 && per > ml) why = sprintf("load %.2f per core (max %s)", per, ml)
        if (ms > 0 && gb > ms) why = why (why ? ", " : "") sprintf("swap %.1f GB used (max %s)", gb, ms)
        if (why) { print why; exit 0 } else exit 1
    }'
}
