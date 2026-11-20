#!/usr/bin/env bash
# Prepares a stable release from the current commit of main (the Stable release workflow runs it; docs/fork-setup.md):
# picks the version, commits a new release line to version.properties when one is asked for, and pushes that commit
# together with the tag v<major>.<minor>.<patch> in one atomic push. The ci and prover runs on the tag publish.
#
#   tools/stable-release.sh keep        release the current line as it is (0.5 with 7 commits -> v0.5.7)
#   tools/stable-release.sh minor       start the next minor line and release its first build (0.5 -> v0.6.0)
#   tools/stable-release.sh major       start the next major line (0.5 -> v1.0.0)
#   tools/stable-release.sh 1.2         start the given line (must not be lower than the current one)
#
# DRY_RUN=1 prints the plan and changes nothing. Writes tag, version and sha to $GITHUB_OUTPUT when it is set.
set -euo pipefail
cd "$(dirname "$0")/.."
choice=${1:-keep}
line_of() { sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*\([0-9]*\.[0-9]*\)[[:space:]]*$/\1/p' version.properties | head -n 1; }
current=$(line_of)
if [ -z "$current" ]; then echo "version.properties needs a line version=<major>.<minor>" >&2; exit 1; fi
if [ "$(git rev-parse --is-shallow-repository)" != false ]; then echo "Needs the full git history (fetch-depth: 0)" >&2; exit 1; fi
major=$((10#${current%.*})); minor=$((10#${current#*.}))
case "$choice" in
    keep) next="$major.$minor" ;;
    minor) next="$major.$((minor + 1))" ;;
    major) next="$((major + 1)).0" ;;
    *)
        if [[ ! "$choice" =~ ^([0-9]+)\.([0-9]+)$ ]]; then
            echo "Expected keep, minor, major or <major>.<minor>, got: $choice" >&2
            exit 1
        fi
        nmaj=$((10#${BASH_REMATCH[1]})); nmin=$((10#${BASH_REMATCH[2]}))
        if [ "$nmin" -gt 99 ]; then echo "The minor version goes up to 99 (the wallet's versionCode rule)" >&2; exit 1; fi
        if [ "$nmaj" -lt "$major" ] || { [ "$nmaj" -eq "$major" ] && [ "$nmin" -lt "$minor" ]; }; then
            echo "Version $nmaj.$nmin is lower than the current line $current: versions only go up" >&2
            exit 1
        fi
        next="$nmaj.$nmin" ;;
esac
if [ "$next" != "$current" ]; then
    patch=0
else
    base=$(git log -G'^version=' -1 --format=%H -- version.properties)
    patch=$(git rev-list --count "$base..HEAD")
fi
version="$next.$patch"
tag="v$version"
if git rev-parse -q --verify "refs/tags/$tag" > /dev/null || git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1; then
    echo "Tag $tag already exists: there is no new commit since that release (or pick minor, major or a version)" >&2
    exit 1
fi
echo "Current line $current, release $tag"
if [ "${DRY_RUN:-}" = 1 ]; then echo "Dry run: nothing committed or pushed"; exit 0; fi
git config user.name "${GIT_AUTHOR_NAME:-github-actions[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
refs=()
if [ "$next" != "$current" ]; then
    sed -i "s/^\([[:space:]]*version[[:space:]]*=[[:space:]]*\)[0-9]*\.[0-9]*[[:space:]]*$/\1$next/" version.properties
    if [ "$(line_of)" != "$next" ]; then echo "Could not write version=$next to version.properties" >&2; exit 1; fi
    git commit -q -m "Version line $next (stable release $tag)" -- version.properties
    refs+=("HEAD:refs/heads/main")
fi
git tag -a "$tag" -m "Stable release $version"
refs+=("refs/tags/$tag")
if ! git push --atomic origin "${refs[@]}"; then
    if [ "$next" != "$current" ]; then
        echo "Push refused. A new release line is a commit to main, which the MUST CI ruleset only lets a bypass actor push:" >&2
        echo "set the secret RELEASE_TOKEN (docs/fork-setup.md), or change version.properties in a pull request and run keep." >&2
    fi
    exit 1
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    { echo "tag=$tag"; echo "version=$version"; echo "sha=$(git rev-parse HEAD)"; } >> "$GITHUB_OUTPUT"
fi
echo "Pushed $tag at $(git rev-parse --short HEAD)"
