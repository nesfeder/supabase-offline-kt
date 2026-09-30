#!/usr/bin/env bash
#
# Sets the repository settings that GitHub offers only on a public repository, on the day the
# repository goes public. The release runbook's "Make the repos public" step runs it last: first
# the self-hosted runner is removed, then the repository is made public, then this script runs.
#
# It sets, through the GitHub REST API:
#   - workflow runs from a fork's pull request wait for approval, for every external contributor;
#   - every action a workflow uses must be pinned to a full-length commit SHA;
#   - private vulnerability reporting is on;
#   - code scanning's default setup is configured for GitHub Actions workflows;
#   - Discussions are on and the wiki is off;
#   - the description, homepage and topics are the ones below.
# It never touches branch protection, rulesets, environments (the release environment's required
# reviewer included), secrets or variables. Those stay manual steps in the runbook.
#
# It refuses to run, and changes nothing, while the repository is private or while any
# self-hosted runner is registered: on a public repository, a fork's pull request can edit a
# workflow to run on a self-hosted runner.
#
# Before --apply, check that every action the workflows use is pinned by SHA, including the actions
# a composite action calls: once pinning is required, a job that uses an action pinned by tag fails.
#
# Usage:
#   scripts/go-public.sh              dry run: each setting with its current value and its target
#   scripts/go-public.sh --dry-run    the same
#   scripts/go-public.sh --apply      set each setting that differs, then read them all back
#   scripts/go-public.sh --self-test  the refusals and the dry run against a fake gh and recorded
#                                     responses; never reaches GitHub
#
# Exit status: 0 done; 1 refused, or a setting still differs after --apply; 2 a usage error, or a
# value that could not be read or written.
#
# Needs the GitHub CLI signed in as a repository administrator; the self-test needs jq instead.
# Portable: bash 3.2 (macOS) or later.
#
# The endpoints, from GitHub's REST reference (https://docs.github.com/rest), all present in API
# version 2022-11-28, the version gh uses when none is given:
#   actions/runners                           "List self-hosted runners for a repository"
#   actions/permissions/fork-pr-contributor-approval
#                                             "Set fork PR contributor approval permissions for a
#                                             repository". The older fork-pr-workflows-private-repos
#                                             endpoint applies to private repositories only.
#   actions/permissions                       "Set GitHub Actions permissions for a repository":
#                                             sha_pinning_required; enabled is required, so the
#                                             current enabled and allowed_actions are sent back
#   private-vulnerability-reporting           "Enable private vulnerability reporting for a
#                                             repository"
#   code-scanning/default-setup               "Update a code scanning default setup configuration"
#   the repository itself                     "Update a repository": has_wiki, description,
#                                             homepage. has_discussions is missing from that page
#                                             but accepted; `gh repo edit --enable-discussions`
#                                             sends it. The read-back below shows if it took.
#   topics                                    "Replace all repository topics"

set -euo pipefail

# What the repository shows once it is public. Edit these before running.
DESCRIPTION='Offline-first PostgREST and Storage for supabase-kt, for Kotlin Multiplatform apps on Android and iOS.'
HOMEPAGE='https://nesfeder.github.io/supabase-offline-kt/'
TOPICS='android ios kotlin kotlin-multiplatform offline-first postgrest supabase swift'

# Only the self-test changes these, to point the script at a fake gh and a repository that does
# not exist.
REPOSITORY="${GO_PUBLIC_REPOSITORY:-nesfeder/supabase-offline-kt}"
GH="${GO_PUBLIC_GH:-gh}"

# The settings, in the order they are shown and applied.
SETTINGS='fork_approval sha_pinning vulnerability_reporting code_scanning discussions wiki description homepage topics'

SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

usage() {
    echo "usage: $0 [--dry-run | --apply | --self-test]" >&2
    exit 2
}

# ─────────────────────────────────────────────────────────────────────────────
# Reading and writing through gh
# ─────────────────────────────────────────────────────────────────────────────

# Prints the result of a jq filter over a GET of an endpoint. gh's error goes to $SCRATCH/error.
gh_read() {
    "$GH" api "$1" --jq "$2" 2> "$SCRATCH/error"
}

# Sends a write through gh, discarding its response. gh's error goes to $SCRATCH/error.
gh_write() {
    "$GH" api "$@" > /dev/null 2> "$SCRATCH/error"
}

# The first line of gh's last error, for a message.
last_error() {
    local line
    line="$(head -n 1 "$SCRATCH/error" 2> /dev/null || true)"
    printf '%s' "${line:-no error message}"
}

# ─────────────────────────────────────────────────────────────────────────────
# The refusal
# ─────────────────────────────────────────────────────────────────────────────

# Exits 1 while the repository is private or a self-hosted runner is registered, and 2 when either
# cannot be read. Nothing else is read before this passes.
refuse_unless_public_without_runners() {
    local private runners reasons=""
    if ! private="$(gh_read "repos/$REPOSITORY" '.private | tostring')"; then
        echo "go-public: could not read whether $REPOSITORY is private ($(last_error))." >&2
        echo "Nothing was changed." >&2
        exit 2
    fi
    if ! runners="$(gh_read "repos/$REPOSITORY/actions/runners" '.total_count | tostring')"; then
        echo "go-public: could not read the self-hosted runners of $REPOSITORY ($(last_error))." >&2
        echo "Nothing was changed." >&2
        exit 2
    fi

    case "$private" in
        false) ;;
        true) reasons="${reasons}  - the repository is private;
" ;;
        *) reasons="${reasons}  - the repository is not known to be public (private reads \"$private\");
" ;;
    esac
    case "$runners" in
        0) ;;
        1) reasons="${reasons}  - 1 self-hosted runner is registered;
" ;;
        *[!0-9]* | '') reasons="${reasons}  - the self-hosted runner count reads \"$runners\", not 0;
" ;;
        *) reasons="${reasons}  - $runners self-hosted runners are registered;
" ;;
    esac

    if [ -n "$reasons" ]; then
        {
            echo "go-public: refusing to run against $REPOSITORY. Nothing was changed, because:"
            printf '%s' "$reasons"
            echo "This script is the last part of the release runbook's \"Make the repos public\" step."
            echo "First remove the self-hosted runner (the registered count must read 0), then make"
            echo "the repository public, then run this script again."
        } >&2
        exit 1
    fi
    echo "Repository: $REPOSITORY (public, no self-hosted runner registered)"
}

# ─────────────────────────────────────────────────────────────────────────────
# Current values
# ─────────────────────────────────────────────────────────────────────────────

# Reads every setting into the variables below. A value that cannot be read is left as "?", its
# error is added to UNREADABLE, and the settings it covers to UNREADABLE_SETTINGS.
load_current_values() {
    local output
    UNREADABLE="" UNREADABLE_SETTINGS=" "
    FORK_POLICY='?'
    ACTIONS_ENABLED='?' ACTIONS_ALLOWED='?' SHA_PINNING='?'
    VULNERABILITY_REPORTING='?'
    SCANNING_STATE='?' SCANNING_LANGUAGES='?'
    CURRENT_DESCRIPTION='?' CURRENT_HOMEPAGE='?' HAS_DISCUSSIONS='?' HAS_WIKI='?'
    CURRENT_TOPICS='?'

    if output="$(gh_read "repos/$REPOSITORY/actions/permissions/fork-pr-contributor-approval" \
        '.approval_policy // "unset"')"; then
        FORK_POLICY="$output"
    else
        note_unreadable fork_approval
    fi

    if output="$(gh_read "repos/$REPOSITORY/actions/permissions" \
        '"\(.enabled) \(.allowed_actions // "unset") \(.sha_pinning_required)"')"; then
        read -r ACTIONS_ENABLED ACTIONS_ALLOWED SHA_PINNING <<< "$output"
    else
        note_unreadable sha_pinning
    fi

    if output="$(gh_read "repos/$REPOSITORY/private-vulnerability-reporting" '.enabled | tostring')"; then
        VULNERABILITY_REPORTING="$output"
    else
        note_unreadable vulnerability_reporting
    fi

    if output="$(gh_read "repos/$REPOSITORY/code-scanning/default-setup" \
        '"\(.state // "unset") \(.languages // [] | join(","))"')"; then
        read -r SCANNING_STATE SCANNING_LANGUAGES <<< "$output"
    else
        note_unreadable code_scanning
    fi

    # The two booleans come last: command substitution drops trailing empty lines, and an empty
    # homepage must still be read as one.
    if ! output="$(gh_read "repos/$REPOSITORY" \
        '(.description // ""), (.homepage // ""), (.has_discussions | tostring), (.has_wiki | tostring)')"; then
        note_unreadable "description homepage discussions wiki"
    elif ! {
        IFS= read -r CURRENT_DESCRIPTION
        IFS= read -r CURRENT_HOMEPAGE
        IFS= read -r HAS_DISCUSSIONS
        IFS= read -r HAS_WIKI
    } <<< "$output"; then
        echo "expected four lines from the repository, got fewer" > "$SCRATCH/error"
        note_unreadable "description homepage discussions wiki"
    fi

    if output="$(gh_read "repos/$REPOSITORY/topics" '.names | sort | join(",")')"; then
        CURRENT_TOPICS="$output"
    else
        note_unreadable topics
    fi
}

# Records that the named settings could not be read, with gh's error.
note_unreadable() {
    UNREADABLE="${UNREADABLE}  - $1: $(last_error)
"
    UNREADABLE_SETTINGS="$UNREADABLE_SETTINGS$1 "
}

# The configured topics, sorted and comma-separated, as the topics read is.
target_topics() {
    local topic
    for topic in $TOPICS; do echo "$topic"; done | LC_ALL=C sort | paste -s -d , -
}

# The code scanning languages to configure: Actions alone, or added to what is already configured.
target_languages() {
    if [ "$SCANNING_STATE" = configured ] && [ -n "$SCANNING_LANGUAGES" ]; then
        case ",$SCANNING_LANGUAGES," in
            *,actions,*) printf '%s' "$SCANNING_LANGUAGES" ;;
            *) printf '%s' "$SCANNING_LANGUAGES,actions" ;;
        esac
    else
        printf '%s' actions
    fi
}

# Sets LABEL, NOW and TARGET for a setting, and SETTING_STATE to set, ok or unreadable.
describe_setting() {
    local satisfied=false
    case "$1" in
        fork_approval)
            LABEL='Workflow runs from fork pull requests wait for approval from'
            NOW="$FORK_POLICY"
            TARGET=all_external_contributors
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        sha_pinning)
            LABEL='Actions must be pinned to a full commit SHA'
            NOW="$SHA_PINNING"
            TARGET=true
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        vulnerability_reporting)
            LABEL='Private vulnerability reporting'
            NOW="$VULNERABILITY_REPORTING"
            TARGET=true
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        code_scanning)
            LABEL='Code scanning default setup'
            if [ -n "$SCANNING_LANGUAGES" ] && [ "$SCANNING_LANGUAGES" != '?' ]; then
                NOW="$SCANNING_STATE ($(printf '%s' "$SCANNING_LANGUAGES" | sed 's/,/, /g'))"
            else
                NOW="$SCANNING_STATE"
            fi
            TARGET="configured ($(target_languages | sed 's/,/, /g'))"
            if [ "$SCANNING_STATE" = configured ]; then
                case ",$SCANNING_LANGUAGES," in *,actions,*) satisfied=true ;; esac
            fi ;;
        discussions)
            LABEL='Discussions'
            NOW="$HAS_DISCUSSIONS"
            TARGET=true
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        wiki)
            LABEL='Wiki'
            NOW="$HAS_WIKI"
            TARGET=false
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        description)
            LABEL='Description'
            NOW="$CURRENT_DESCRIPTION"
            TARGET="$DESCRIPTION"
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        homepage)
            LABEL='Homepage'
            NOW="$CURRENT_HOMEPAGE"
            TARGET="$HOMEPAGE"
            [ "$NOW" = "$TARGET" ] && satisfied=true ;;
        topics)
            LABEL='Topics'
            NOW="$(printf '%s' "$CURRENT_TOPICS" | sed 's/,/, /g')"
            TARGET="$(target_topics | sed 's/,/, /g')"
            [ "$CURRENT_TOPICS" = "$(target_topics)" ] && satisfied=true ;;
    esac
    if unreadable "$1"; then
        SETTING_STATE='unreadable'
    elif [ "$satisfied" = true ]; then
        SETTING_STATE='ok'
    else
        SETTING_STATE='set'
    fi
}

# Whether the named setting could not be read.
unreadable() {
    case "$UNREADABLE_SETTINGS" in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# Prints every setting and counts them into TO_SET and ALREADY_SET; CHANGES lists those to set.
print_settings() {
    local setting
    TO_SET=0 ALREADY_SET=0 CHANGES=""
    for setting in $SETTINGS; do
        describe_setting "$setting"
        case "$SETTING_STATE" in
            set) TO_SET=$((TO_SET + 1)); CHANGES="$CHANGES $setting" ;;
            ok) ALREADY_SET=$((ALREADY_SET + 1)) ;;
        esac
        printf '  %-10s %s\n' "$SETTING_STATE" "$LABEL"
        if [ "$SETTING_STATE" = unreadable ]; then
            printf '  %-10s   now     %s\n' '' '(could not be read)'
        else
            printf '  %-10s   now     %s\n' '' "${NOW:-(empty)}"
        fi
        printf '  %-10s   target  %s\n' '' "${TARGET:-(empty)}"
    done
    if [ -n "$UNREADABLE" ]; then
        echo
        echo "Could not read:"
        printf '%s' "$UNREADABLE"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Writing
# ─────────────────────────────────────────────────────────────────────────────

# Sends the write for one setting.
write_setting() {
    local language topic
    case "$1" in
        fork_approval)
            gh_write -X PUT "repos/$REPOSITORY/actions/permissions/fork-pr-contributor-approval" \
                -f approval_policy=all_external_contributors ;;
        sha_pinning)
            set -- -X PUT "repos/$REPOSITORY/actions/permissions" -F "enabled=$ACTIONS_ENABLED"
            if [ "$ACTIONS_ALLOWED" != unset ]; then set -- "$@" -f "allowed_actions=$ACTIONS_ALLOWED"; fi
            gh_write "$@" -F sha_pinning_required=true ;;
        vulnerability_reporting)
            gh_write -X PUT "repos/$REPOSITORY/private-vulnerability-reporting" ;;
        code_scanning)
            set -- -X PATCH "repos/$REPOSITORY/code-scanning/default-setup" -f state=configured
            for language in $(target_languages | tr , ' '); do
                set -- "$@" -f "languages[]=$language"
            done
            # A setup already configured keeps its query suite; a new one gets the default.
            if [ "$SCANNING_STATE" != configured ]; then set -- "$@" -f query_suite=default; fi
            gh_write "$@" ;;
        discussions)
            gh_write -X PATCH "repos/$REPOSITORY" -F has_discussions=true ;;
        wiki)
            gh_write -X PATCH "repos/$REPOSITORY" -F has_wiki=false ;;
        description)
            gh_write -X PATCH "repos/$REPOSITORY" -f "description=$DESCRIPTION" ;;
        homepage)
            gh_write -X PATCH "repos/$REPOSITORY" -f "homepage=$HOMEPAGE" ;;
        topics)
            set -- -X PUT "repos/$REPOSITORY/topics"
            for topic in $TOPICS; do set -- "$@" -f "names[]=$topic"; done
            gh_write "$@" ;;
    esac
}

# Writes every setting in CHANGES, then reads everything back. Exits 2 if a write failed and 1 if a
# setting still reads differently.
apply_changes() {
    local setting failed=0
    echo
    echo "Applying $TO_SET setting(s):"
    for setting in $CHANGES; do
        describe_setting "$setting"
        if write_setting "$setting"; then
            echo "  done       $LABEL"
        else
            echo "  FAILED     $LABEL: $(last_error)"
            failed=$((failed + 1))
        fi
    done

    echo
    echo "Read back after applying:"
    load_current_values
    print_settings
    echo
    echo "$TO_SET to set, $ALREADY_SET already set."
    if [ "$failed" -gt 0 ]; then
        echo
        echo "$failed write(s) failed; see above. Run again to retry what is still to set."
        exit 2
    fi
    if [ "$TO_SET" -gt 0 ] || [ -n "$UNREADABLE" ]; then
        echo
        echo "Every write was accepted, but $TO_SET setting(s) still read differently. Code scanning's"
        echo "default setup is configured in the background; run the dry run again in a few minutes."
        exit 1
    fi
    echo
    echo "Every setting reads as its target."
}

print_manual_steps() {
    echo
    echo "Left to the runbook, by hand: branch protection, the release environment's required"
    echo "reviewer, secrets and variables, release immutability, the Pages source, and making the"
    echo "Swift package's repository public."
}

main() {
    local mode="$1"
    SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/go-public.XXXXXX")"
    trap 'rm -rf "${SCRATCH:?}"' EXIT
    if ! command -v "$GH" > /dev/null 2>&1; then
        echo "go-public: the GitHub CLI (gh) is not installed or not on PATH." >&2
        exit 2
    fi

    refuse_unless_public_without_runners
    if [ "$mode" = apply ]; then
        echo "Mode: apply. The rows marked \"set\" are set now."
    else
        echo "Mode: dry run. Nothing is changed; run with --apply to set the rows marked \"set\"."
    fi
    echo

    load_current_values
    print_settings
    echo
    echo "$TO_SET to set, $ALREADY_SET already set."
    if [ -n "$UNREADABLE" ]; then
        echo "Some settings could not be read, so nothing is applied. Nothing was changed."
        exit 2
    fi
    if [ "$mode" = apply ] && [ "$TO_SET" -gt 0 ]; then
        apply_changes
    fi
    print_manual_steps
}

# ─────────────────────────────────────────────────────────────────────────────
# Self-test: the script runs against a fake gh that answers from recorded responses and logs every
# call. It proves the refusals change and read nothing further, the dry run writes nothing, and
# --apply sends exactly the expected writes. The environment points gh itself at a host that does
# not exist, so nothing can reach GitHub even if the fake were bypassed.
# ─────────────────────────────────────────────────────────────────────────────

# Writes the fake gh. It answers `gh api` GETs from $FAKE_GH_RESPONSES, a file per endpoint with
# `/` as `__`, and after a write to an endpoint answers from its `.applied.json` if there is one.
# An `.error` file makes the endpoint fail with that message. Every call is logged.
write_fake_gh() {
    mkdir -p "$1"
    printf '#!%s\n' "$BASH" > "$1/gh"
    cat >> "$1/gh" <<'FAKE'
set -u
if [ "${1:-}" != api ]; then echo "fake gh: only 'gh api' is recorded" >&2; exit 64; fi
shift
method=GET endpoint="" filter="" fields=""
while [ $# -gt 0 ]; do
    case "$1" in
        -X | --method) method="$2"; shift 2 ;;
        --jq) filter="$2"; shift 2 ;;
        -f | -F) fields="$fields $1 $2"; shift 2 ;;
        -*) echo "fake gh: unexpected flag $1" >&2; exit 64 ;;
        *) endpoint="$1"; shift ;;
    esac
done
echo "$method $endpoint$fields" >> "$FAKE_GH_LOG"
key="$(printf '%s' "$endpoint" | sed 's#/#__#g')"
if [ "$method" != GET ]; then
    : > "$FAKE_GH_STATE/$key.written"
    exit 0
fi
if [ -e "$FAKE_GH_RESPONSES/$key.error" ]; then cat "$FAKE_GH_RESPONSES/$key.error" >&2; exit 1; fi
response="$FAKE_GH_RESPONSES/$key.json"
if [ -e "$FAKE_GH_STATE/$key.written" ] && [ -e "$FAKE_GH_RESPONSES/$key.applied.json" ]; then
    response="$FAKE_GH_RESPONSES/$key.applied.json"
fi
if [ ! -e "$response" ]; then echo "gh: Not Found (HTTP 404)" >&2; exit 1; fi
jq -r "${filter:-.}" "$response"
FAKE
    chmod +x "$1/gh"
}

# Records a response: scenario directory, endpoint below the repository ("" for the repository
# itself), and the JSON body, or `error:<message>` for a failing endpoint. A third `.applied`
# argument before the body records the response after a write.
record() {
    local directory="$1" suffix="$2" kind=json body="$3" key
    if [ "$3" = applied ]; then kind=applied.json; body="$4"; fi
    key="$(printf '%s' "repos/self-test/offline-kt$suffix" | sed 's#/#__#g')"
    mkdir -p "$directory"
    case "$body" in
        error:*) printf '%s\n' "${body#error:}" > "$directory/$key.error" ;;
        *) printf '%s\n' "$body" > "$directory/$key.$kind" ;;
    esac
}

# The repository object, trimmed to the fields the script reads and a few beside them. Arguments:
# private, description, homepage, has_discussions and has_wiki, each as JSON.
repository_json() {
    local visibility=public
    if [ "$1" = true ]; then visibility=private; fi
    printf '{"full_name":"self-test/offline-kt","private":%s,"visibility":"%s","description":%s,"homepage":%s,"has_issues":true,"has_discussions":%s,"has_wiki":%s,"topics":[]}' \
        "$1" "$visibility" "$2" "$3" "$4" "$5"
}

# Records every scenario the cases below use.
record_scenarios() {
    local root="$1" target_description target_homepage
    target_description="\"$DESCRIPTION\""
    target_homepage="\"$HOMEPAGE\""

    # Today: private, with the Mac's runner registered.
    record "$root/today" "" "$(repository_json true '"Offline-first Supabase"' null false true)"
    record "$root/today" /actions/runners '{"total_count":1,"runners":[{"id":7,"name":"runner-one","os":"macOS","status":"online","busy":false,"labels":[{"name":"self-hosted"}]}]}'

    record "$root/private-only" "" "$(repository_json true '"Offline-first Supabase"' null false true)"
    record "$root/private-only" /actions/runners '{"total_count":0,"runners":[]}'

    record "$root/runners-only" "" "$(repository_json false '"Offline-first Supabase"' null false true)"
    record "$root/runners-only" /actions/runners '{"total_count":2,"runners":[]}'

    record "$root/runners-unreadable" "" "$(repository_json false '"Offline-first Supabase"' null false true)"
    record "$root/runners-unreadable" /actions/runners 'error:gh: Must have admin rights to Repository. (HTTP 403)'

    # Just made public: GitHub's defaults, an old description, the homepage already right.
    local fresh="$root/fresh"
    record "$fresh" "" "$(repository_json false '"Offline-first Supabase"' "$target_homepage" false true)"
    record "$fresh" "" applied "$(repository_json false "$target_description" "$target_homepage" true false)"
    record "$fresh" /actions/runners '{"total_count":0,"runners":[]}'
    record "$fresh" /actions/permissions/fork-pr-contributor-approval '{"approval_policy":"first_time_contributors"}'
    record "$fresh" /actions/permissions/fork-pr-contributor-approval applied '{"approval_policy":"all_external_contributors"}'
    record "$fresh" /actions/permissions '{"enabled":true,"allowed_actions":"all","sha_pinning_required":false}'
    record "$fresh" /actions/permissions applied '{"enabled":true,"allowed_actions":"all","sha_pinning_required":true}'
    record "$fresh" /private-vulnerability-reporting '{"enabled":false}'
    record "$fresh" /private-vulnerability-reporting applied '{"enabled":true}'
    record "$fresh" /code-scanning/default-setup '{"state":"not-configured","languages":[],"query_suite":"default","updated_at":null,"schedule":null}'
    record "$fresh" /code-scanning/default-setup applied '{"state":"configured","languages":["actions"],"query_suite":"default","updated_at":"2026-10-01T09:00:00Z","schedule":"weekly"}'
    record "$fresh" /topics '{"names":[]}'
    record "$fresh" /topics applied "{\"names\":[$(target_topics | sed 's/[^,]*/"&"/g')]}"

    # Everything already at its target.
    local already_set="$root/already-set"
    record "$already_set" "" "$(repository_json false "$target_description" "$target_homepage" true false)"
    record "$already_set" /actions/runners '{"total_count":0,"runners":[]}'
    record "$already_set" /actions/permissions/fork-pr-contributor-approval '{"approval_policy":"all_external_contributors"}'
    record "$already_set" /actions/permissions '{"enabled":true,"allowed_actions":"all","sha_pinning_required":true}'
    record "$already_set" /private-vulnerability-reporting '{"enabled":true}'
    record "$already_set" /code-scanning/default-setup '{"state":"configured","languages":["actions"],"query_suite":"default","updated_at":"2026-10-01T09:00:00Z","schedule":"weekly"}'
    record "$already_set" /topics "{\"names\":[$(target_topics | sed 's/[^,]*/"&"/g')]}"

    # The owner already chose a narrower actions policy and another code scanning language: the
    # writes keep both.
    cp -R "$already_set" "$root/customized"
    record "$root/customized" /actions/permissions '{"enabled":true,"allowed_actions":"selected","selected_actions_url":"https://api.github.com/repos/self-test/offline-kt/actions/permissions/selected-actions","sha_pinning_required":false}'
    record "$root/customized" /actions/permissions applied '{"enabled":true,"allowed_actions":"selected","sha_pinning_required":true}'
    record "$root/customized" /code-scanning/default-setup '{"state":"configured","languages":["java-kotlin"],"query_suite":"extended","updated_at":"2026-10-01T09:00:00Z","schedule":"weekly"}'
    record "$root/customized" /code-scanning/default-setup applied '{"state":"configured","languages":["actions","java-kotlin"],"query_suite":"extended","updated_at":"2026-10-01T09:05:00Z","schedule":"weekly"}'

    # Code scanning's configuration cannot be read.
    cp -R "$fresh" "$root/unreadable"
    rm "$root/unreadable/repos__self-test__offline-kt__code-scanning__default-setup.json"
    record "$root/unreadable" /code-scanning/default-setup 'error:gh: Code scanning is not enabled for this repository. (HTTP 403)'
}

run_self_test() {
    if ! command -v jq > /dev/null 2>&1; then
        echo "go-public: the self-test needs jq, which answers the fake gh's --jq filters." >&2
        exit 2
    fi
    local work failures=0 passed=0 repository=repos/self-test/offline-kt topic
    work="$(mktemp -d "${TMPDIR:-/tmp}/go-public-self-test.XXXXXX")"
    SELF_TEST_WORK="$work"
    trap 'rm -rf "${SELF_TEST_WORK:?}"' EXIT
    write_fake_gh "$work/bin"
    record_scenarios "$work/responses"
    mkdir -p "$work/gh-config"

    # Runs the script against a scenario; sets OUTPUT, STATUS and CALLS (the fake's log).
    run_case() {
        local scenario="$1"
        shift
        rm -rf "${work:?}/state" "${work:?}/log"
        mkdir -p "$work/state"
        : > "$work/log"
        STATUS=0
        OUTPUT="$(env GO_PUBLIC_GH="$work/bin/gh" GO_PUBLIC_REPOSITORY=self-test/offline-kt \
            PATH="$work/bin:$PATH" GH_HOST=self-test.invalid GH_TOKEN=self-test-not-a-token \
            GH_CONFIG_DIR="$work/gh-config" FAKE_GH_LOG="$work/log" \
            FAKE_GH_RESPONSES="$work/responses/$scenario" FAKE_GH_STATE="$work/state" \
            "$BASH" "$SCRIPT_PATH" "$@" 2>&1)" || STATUS=$?
        CALLS="$(cat "$work/log")"
    }

    # Reports one case: its name, then a condition that must hold.
    check() {
        local name="$1"
        shift
        if "$@"; then
            echo "ok   $name"
            passed=$((passed + 1))
        else
            echo "FAIL $name (exit $STATUS)"
            printf '%s\n' "$OUTPUT" | sed 's/^/       /'
            echo "       calls to the fake gh:"
            printf '%s\n' "$CALLS" | sed 's/^/         /'
            failures=$((failures + 1))
        fi
    }

    has() { printf '%s\n' "$OUTPUT" | grep -F -q -- "$1"; }
    lacks() { ! has "$1"; }
    count_rows() { printf '%s\n' "$OUTPUT" | grep -c "^  $1 " || true; }
    writes() { printf '%s\n' "$CALLS" | grep -v '^GET ' || true; }
    only_the_two_checks() {
        [ "$CALLS" = "GET $repository
GET $repository/actions/runners" ]
    }

    refused_for_both_reasons() {
        [ "$STATUS" -eq 1 ] && has "the repository is private;" \
            && has "1 self-hosted runner is registered;" && has '"Make the repos public" step' \
            && has "Nothing was changed" && only_the_two_checks
    }
    run_case today
    check "private with a runner: the dry run refuses, naming both reasons and the runbook step" \
        refused_for_both_reasons
    run_case today --apply
    check "private with a runner: --apply refuses the same way and sends no write" \
        refused_for_both_reasons

    refused_as_private() {
        [ "$STATUS" -eq 1 ] && has "the repository is private;" && lacks "runner is registered" \
            && lacks "runners are registered" && only_the_two_checks
    }
    run_case private-only --apply
    check "private without a runner: --apply refuses because it is private" refused_as_private

    refused_for_runners() {
        [ "$STATUS" -eq 1 ] && has "2 self-hosted runners are registered;" && lacks "is private" \
            && only_the_two_checks
    }
    run_case runners-only --apply
    check "public with two runners: --apply refuses because of the runners" refused_for_runners

    stopped_on_unreadable_runners() {
        [ "$STATUS" -eq 2 ] && has "could not read the self-hosted runners" \
            && has "Must have admin rights" && has "Nothing was changed" && only_the_two_checks
    }
    run_case runners-unreadable --apply
    check "runner count unreadable: --apply stops with status 2 and changes nothing" \
        stopped_on_unreadable_runners

    local expected_dry_run
    printed_every_setting() {
        [ "$STATUS" -eq 0 ] && [ -z "$(writes)" ] && has "Mode: dry run" \
            && has "8 to set, 1 already set." \
            && [ "$(count_rows set)" -eq 8 ] && [ "$(count_rows ok)" -eq 1 ] \
            && has "now     first_time_contributors" && has "target  all_external_contributors" \
            && has "now     false" && has "now     true" \
            && has "now     not-configured" && has "target  configured (actions)" \
            && has "now     Offline-first Supabase" && has "target  $DESCRIPTION" \
            && has "now     $HOMEPAGE" \
            && has "now     (empty)" && has "target  $(target_topics | sed 's/,/, /g')" \
            && has "Left to the runbook"
    }
    run_case fresh
    expected_dry_run="$OUTPUT"
    check "just made public: the dry run prints every setting, its value and its target, and writes nothing" \
        printed_every_setting

    same_as_no_argument() {
        [ "$STATUS" -eq 0 ] && [ "$OUTPUT" = "$expected_dry_run" ] && [ -z "$(writes)" ]
    }
    run_case fresh --dry-run
    check "--dry-run prints exactly what no argument prints" same_as_no_argument

    nothing_to_set() {
        [ "$STATUS" -eq 0 ] && has "0 to set, 9 already set." && [ "$(count_rows ok)" -eq 9 ] \
            && [ -z "$(writes)" ] && lacks "Applying"
    }
    run_case already-set
    check "everything already set: the dry run reports nothing to set" nothing_to_set
    run_case already-set --apply
    check "everything already set: --apply sends no write" nothing_to_set

    stopped_before_any_write() {
        [ "$STATUS" -eq 2 ] && has "(could not be read)" && has "Code scanning is not enabled" \
            && has "nothing is applied" && [ -z "$(writes)" ]
    }
    run_case unreadable --apply
    check "a setting unreadable: --apply stops with status 2 before any write" \
        stopped_before_any_write

    # The homepage is already right, so it is the one setting not written.
    local expected_writes topic_fields=""
    for topic in $TOPICS; do topic_fields="$topic_fields -f names[]=$topic"; done
    expected_writes="PUT $repository/actions/permissions/fork-pr-contributor-approval -f approval_policy=all_external_contributors
PUT $repository/actions/permissions -F enabled=true -f allowed_actions=all -F sha_pinning_required=true
PUT $repository/private-vulnerability-reporting
PATCH $repository/code-scanning/default-setup -f state=configured -f languages[]=actions -f query_suite=default
PATCH $repository -F has_discussions=true
PATCH $repository -F has_wiki=false
PATCH $repository -f description=$DESCRIPTION
PUT $repository/topics$topic_fields"
    applied_and_read_back() {
        [ "$STATUS" -eq 0 ] && [ "$(writes)" = "$expected_writes" ] && has "Applying 8 setting(s):" \
            && has "Every setting reads as its target." && has "0 to set, 9 already set."
    }
    run_case fresh --apply
    check "just made public: --apply sends exactly the eight writes, in order, and reads them back" \
        applied_and_read_back

    kept_the_owner_choices() {
        [ "$STATUS" -eq 0 ] && [ "$(writes)" = "PUT $repository/actions/permissions -F enabled=true -f allowed_actions=selected -F sha_pinning_required=true
PATCH $repository/code-scanning/default-setup -f state=configured -f languages[]=java-kotlin -f languages[]=actions" ] \
            && has "target  configured (java-kotlin, actions)" \
            && has "Every setting reads as its target."
    }
    run_case customized --apply
    check "--apply keeps the chosen actions policy and query suite, and adds Actions to the languages" \
        kept_the_owner_choices

    rejected_the_argument() {
        [ "$STATUS" -eq 2 ] && has "usage:" && [ -z "$CALLS" ]
    }
    run_case fresh --bogus
    check "an unknown argument: usage, status 2, and no call to gh" rejected_the_argument

    if [ "$failures" -eq 0 ]; then
        echo "SELF-TEST PASSED: $passed cases."
    else
        echo "SELF-TEST FAILED: $failures of $((passed + failures)) cases."
        exit 1
    fi
}

case "${1:-}" in
    "" | --dry-run) [ $# -le 1 ] || usage; main dry-run ;;
    --apply) [ $# -eq 1 ] || usage; main apply ;;
    --self-test) [ $# -eq 1 ] || usage; run_self_test ;;
    *) usage ;;
esac
