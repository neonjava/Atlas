#!/usr/bin/env bash
# build.sh — compiles the AtlasProbe plugin against a specific server.
#
# Why: the probe reads Folia internals, so it must be compiled against the
# exact server it measures. Paperclip unpacks the server jar and its
# libraries into a run directory on first start; that is the classpath.
# Done: javac + jar, no Gradle needed.
# Not done: no caching; it rebuilds every time (takes a few seconds).
#
# Usage: build.sh <server-run-dir> <out.jar>
set -euo pipefail
run_dir="$1"
out_jar="$2"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
classpath="$(find "$run_dir/versions" "$run_dir/libraries" -name '*.jar' | paste -sd:)"
javac -nowarn -d "$work/classes" -cp "$classpath" $(find "$here/src" -name '*.java')
cp "$here/resources/plugin.yml" "$work/classes/"
jar --create --file "$out_jar" -C "$work/classes" .
