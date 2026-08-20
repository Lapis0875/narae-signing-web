#!/bin/sh
set -eu
grep -q 'node-version: "22"' .github/workflows/ci.yml
grep -q 'java-version: "21"' .github/workflows/ci.yml
grep -q 'image: postgres:16' .github/workflows/ci.yml
echo "workflow runtime pins: valid"
