#!/usr/bin/env bash
#
# Copyright 2008-2026 Async-IO.org
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
#
# Labels every issue a release fixed with the release version, the repository's
# convention since 2.x (labels `2.7.14`, `3.0.12`, ...).
#
# The issues come from two places:
#   - the commits between the previous `atmosphere-*` tag and the release: every
#     `#N` a commit message mentions, whatever the wording, since a fix lands as
#     `Fixes #N` as often as `(#N)`. A merged pull request contributes the
#     issues it closed;
#   - the issues closed by hand since the previous release, when a maintainer's
#     comment cites a commit of the release ("Fixed on main in 4b3b952ae4").
# A mention is not a fix, so an issue is labelled only when it is closed as
# completed, and closed after the previous release branched off main. Every
# skipped number is printed with the reason, and any API failure other than a
# missing issue fails the run: a release whose issues went unlabelled must show
# red so it is re-run by hand.
#
# The previous release is found by version order, not ancestry: release-4x tags
# its release commit and then rebases it onto main when main moved during the
# run, so a tag can sit on a commit main never contains (atmosphere-4.0.25 and
# atmosphere-4.0.35 do). `git describe` would then skip that release and hand
# its issues to the next one.
#
# Usage:
#   release-label-issues.sh <version> [--to <ref>] [--from <ref>] [--dry-run]
#       <version>   the released version; the label is named after it
#       --to        last commit of the release (default: tag atmosphere-<version>)
#       --from      exclusive start (default: the highest atmosphere-* tag
#                   below <version>)
#       --dry-run   resolve and print, create and apply nothing
#   release-label-issues.sh refs <from> <to>
#       print the issue numbers the commits in <from>..<to> mention; no API call
#   release-label-issues.sh cites <from> <to> < text
#       print the commits of <from>..<to> the text names by hash; no API call
#
# GH_TOKEN (or a gh login) needs issues:write. GITHUB_REPOSITORY picks the
# repository (default Atmosphere/atmosphere).

set -euo pipefail

REPO="${GITHUB_REPOSITORY:-Atmosphere/atmosphere}"
LABEL_COLOR="D8DFBA"

die() { echo "release-label-issues: $*" >&2; exit 1; }

# refs <from> <to>: the numbers mentioned as `#N` in the full messages of the
# commits in <from>..<to>. A `#` glued to a word or a path is another tracker
# (`carnet#76`, `Atmosphere/atmosphere-carnet#5`) or an anchor (`page#12`).
refs() {
    git log --format='%B' "$1..$2" \
        | perl -nle 'print $1 while /(?<![\w\/.#-])#(\d+)\b/g' \
        | sort -un
}

# cites <from> <to>: the commits of <from>..<to> that stdin names by an
# abbreviated or full hash. A hex word that is no commit, or one outside the
# range, is dropped.
cites() {
    local word sha
    for word in $(perl -nle 'print lc $1 while /\b([0-9a-fA-F]{7,40})\b/g' | sort -u); do
        sha=$(git rev-parse -q --verify "$word^{commit}" 2> /dev/null) || continue
        if git merge-base --is-ancestor "$sha" "$2" && ! git merge-base --is-ancestor "$sha" "$1"; then
            echo "$sha"
        fi
    done
}

# previous_tag <version>: the highest release tag below <version>. A
# pre-release (`4.0.0-RC1`) sorts below its final version.
previous_tag() {
    git tag -l 'atmosphere-[0-9]*' | perl -e '
        sub key { my ($core, $pre) = split /-/, $_[0], 2;
                  return ((split /\./, $core), defined $pre ? 0 : 1, $pre // ""); }
        sub cmpv { my @x = key($_[0]); my @y = key($_[1]);
                   for my $i (0 .. 3) { my $c = $x[$i] <=> $y[$i]; return $c if $c; }
                   return $x[4] cmp $y[4]; }
        my ($target, $best) = ($ARGV[0], undef);
        while (my $tag = <STDIN>) {
            chomp $tag;
            next unless $tag =~ /^atmosphere-(\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?)$/;
            my $v = $1;
            next unless cmpv($v, $target) < 0;
            $best = $v if !defined $best || cmpv($v, $best) > 0;
        }
        print "atmosphere-$best\n" if defined $best;' "$1"
}

# api_or_missing <path>: the resource and 0; 1 when GitHub answers 404/410 (a
# number that is no issue here, a deleted or transferred one); 2 on any other
# failure. It runs in a command substitution, where `die` would only end the
# subshell, so the caller turns 2 into the failure.
api_or_missing() {
    local err
    err=$(mktemp)
    if gh api "$1" 2> "$err"; then
        rm -f "$err"
        return 0
    fi
    if grep -qE 'HTTP 4(04|10)' "$err"; then
        rm -f "$err"
        return 1
    fi
    cat "$err" >&2
    rm -f "$err"
    return 2
}

if [ "${1:-}" = "refs" ]; then
    [ $# -eq 3 ] || die "usage: $0 refs <from> <to>"
    refs "$2" "$3"
    exit 0
fi
if [ "${1:-}" = "cites" ]; then
    [ $# -eq 3 ] || die "usage: $0 cites <from> <to> < text"
    cites "$2" "$3"
    exit 0
fi

VERSION="${1:-}"
[ -n "$VERSION" ] || die "usage: $0 <version> [--to <ref>] [--from <ref>] [--dry-run]"
shift
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] \
    || die "'$VERSION' is not a release version"

TO=""
FROM=""
DRY_RUN=false
while [ $# -gt 0 ]; do
    case "$1" in
        --to) TO="${2:?--to needs a ref}"; shift 2 ;;
        --from) FROM="${2:?--from needs a ref}"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        *) die "unknown argument '$1'" ;;
    esac
done

if [ -z "$TO" ]; then
    TO="atmosphere-$VERSION"
    git rev-parse -q --verify "refs/tags/$TO" > /dev/null \
        || die "tag $TO does not exist; pass --to <ref> to label from another commit"
fi
git rev-parse -q --verify "$TO^{commit}" > /dev/null || die "--to '$TO' is not a commit"
if [ -z "$FROM" ]; then
    FROM=$(previous_tag "$VERSION")
    [ -n "$FROM" ] || die "no atmosphere-* tag below $VERSION; pass --from"
fi
git rev-parse -q --verify "$FROM^{commit}" > /dev/null || die "--from '$FROM' is not a commit"

# An issue closed before the previous release branched off main was fixed by
# that release. The merge base, not the tag: a tag the release run left off main
# does not contain what was pushed during that run, and this release ships it.
BASE=$(git merge-base "$FROM" "$TO") || die "$FROM and $TO share no history"
SINCE=$(TZ=UTC git log -1 --date=format-local:'%Y-%m-%dT%H:%M:%SZ' --format=%cd "$BASE")
OWNER="${REPO%%/*}"
NAME="${REPO#*/}"

echo "Release $VERSION: commits $FROM..$TO, issues closed after $SINCE, repository $REPO"
$DRY_RUN && echo "(dry run: nothing is created or applied)"

MENTIONED=$(refs "$FROM" "$TO") || die "cannot read the commits of $FROM..$TO"
CANDIDATES=""
for n in $MENTIONED; do
    item=$(api_or_missing "repos/$REPO/issues/$n") && found=0 || found=$?
    case $found in
        0) ;;
        1) echo "  #$n: skipped, not an issue or pull request of $REPO"; continue ;;
        *) die "cannot read #$n" ;;
    esac
    if [ "$(jq -r 'has("pull_request")' <<< "$item")" = "true" ]; then
        if [ "$(jq -r '.pull_request.merged_at // ""' <<< "$item")" = "" ]; then
            echo "  #$n: skipped, pull request not merged"
            continue
        fi
        # shellcheck disable=SC2016 # GraphQL variables, not shell ones
        closed=$(gh api graphql -F owner="$OWNER" -F name="$NAME" -F number="$n" -f query='
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  closingIssuesReferences(first: 50) {
                    nodes { number repository { nameWithOwner } }
                  }
                }
              }
            }' --jq ".data.repository.pullRequest.closingIssuesReferences.nodes[]
                     | select(.repository.nameWithOwner == \"$REPO\") | .number") \
            || die "cannot read the issues pull request #$n closes"
        if [ -z "$closed" ]; then
            echo "  #$n: skipped, pull request closes no issue"
            continue
        fi
        echo "  #$n: pull request closing $(echo "$closed" | sed 's/^/#/' | tr '\n' ' ')"
        CANDIDATES="$CANDIDATES $closed"
    else
        CANDIDATES="$CANDIDATES $n"
    fi
done

# Issues closed by hand: the search index lags a close by seconds, never by the
# length of a release run. Only a maintainer's comment counts as a citation.
CLOSED=$(gh api -X GET search/issues --paginate \
        -f q="repo:$REPO is:issue is:closed reason:completed closed:>=$SINCE" \
        --jq '.items[].number') || die "cannot search the issues closed since $SINCE"
for n in $CLOSED; do
    case " $CANDIDATES " in *" $n "*) continue ;; esac
    comments=$(gh api "repos/$REPO/issues/$n/comments" --paginate --jq '.[]
            | select(.author_association == "OWNER" or .author_association == "MEMBER"
                     or .author_association == "COLLABORATOR") | .body') \
        || die "cannot read the comments of issue #$n"
    cited=$(cites "$FROM" "$TO" <<< "$comments")
    if [ -n "$cited" ]; then
        echo "  #$n: closed citing $(echo "$cited" | head -1 | cut -c1-10)"
        CANDIDATES="$CANDIDATES $n"
    else
        echo "  #$n: skipped, closed without a commit of this release in its comments"
    fi
done

LABELLED=""
for n in $(echo "$CANDIDATES" | tr ' ' '\n' | sed '/^$/d' | sort -un); do
    item=$(gh api "repos/$REPO/issues/$n") || die "cannot read issue #$n"
    read -r state reason closed_at <<< "$(jq -r '[.state, (.state_reason // "completed"), (.closed_at // "-")] | @tsv' <<< "$item")"
    title=$(jq -r '.title' <<< "$item")
    if [ "$state" != "closed" ]; then
        echo "  #$n: skipped, still open ($title)"
    elif [ "$reason" != "completed" ]; then
        echo "  #$n: skipped, closed as $reason ($title)"
    elif [[ "$closed_at" < "$SINCE" ]]; then
        echo "  #$n: skipped, closed $closed_at, before $FROM branched off ($title)"
    else
        echo "  #$n: $VERSION ($title)"
        LABELLED="$LABELLED #$n"
    fi
done

if [ -z "$LABELLED" ]; then
    echo "No fixed issue found for $VERSION."
    exit 0
fi

if ! $DRY_RUN; then
    if ! gh api "repos/$REPO/labels/$VERSION" > /dev/null 2>&1; then
        gh api -X POST "repos/$REPO/labels" -f name="$VERSION" -f color="$LABEL_COLOR" \
            -f description="Fixed in Atmosphere $VERSION" > /dev/null
    fi
    for ref in $LABELLED; do
        gh api -X POST "repos/$REPO/issues/${ref#\#}/labels" -f "labels[]=$VERSION" > /dev/null
    done
fi

SUMMARY="$($DRY_RUN && echo "Would label" || echo "Labelled") with \`$VERSION\`:$LABELLED"
echo "$SUMMARY"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"
fi
