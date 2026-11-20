#!/usr/bin/env bash
# Prints APP_VERSION=<version> for the CI file names, by the rule in build.gradle.kts: version.properties holds
# major.minor, the patch is the number of commits since the commit that last changed that line. Needs the full git
# history (actions/checkout with fetch-depth: 0) and fails without it, so a published file never carries a wrong version.
# On a v* tag (a stable release, tools/stable-release.sh) the tag must name exactly this version.
set -euo pipefail
cd "$(dirname "$0")/.."
line=$(sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*\([0-9]*\.[0-9]*\)[[:space:]]*$/\1/p' version.properties | head -n 1)
if [ -z "$line" ]; then echo "version.properties needs a line version=<major>.<minor>" >&2; exit 1; fi
if [ "$(git rev-parse --is-shallow-repository)" != false ]; then
    echo "The version needs the full git history: check out with fetch-depth: 0" >&2
    exit 1
fi
base=$(git log -G'^version=' -1 --format=%H -- version.properties)
if [ -z "$base" ]; then echo "No commit sets the version line in version.properties" >&2; exit 1; fi
patch=$(git rev-list --count "$base..HEAD")
suffix=""
if [ "${GITHUB_ACTIONS:-}" != true ]; then
    suffix="-dev"
elif [[ "${GITHUB_REF:-}" =~ ^refs/pull/([0-9]+)/ ]]; then
    suffix="-pr${BASH_REMATCH[1]}"
elif [[ "${GITHUB_REF:-}" == refs/tags/v* ]] && [ "${GITHUB_REF#refs/tags/v}" != "$line.$patch" ]; then
    echo "Tag ${GITHUB_REF#refs/tags/} does not match the version $line.$patch of this commit" >&2
    exit 1
fi
echo "APP_VERSION=$line.$patch$suffix"
