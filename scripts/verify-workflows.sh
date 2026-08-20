#!/bin/sh
set -eu

[ "$#" -gt 0 ] || {
    echo "usage: $0 WORKFLOW..." >&2
    exit 64
}

for workflow in "$@"; do
    case "$workflow" in
        -*)
            echo "workflow path must not start with '-': $workflow" >&2
            exit 64
            ;;
        *.yml|*.yaml)
            ;;
        *)
            echo "workflow path must end in .yml or .yaml: $workflow" >&2
            exit 64
            ;;
    esac
    [ -f "$workflow" ] || {
        echo "workflow file does not exist or is not regular: $workflow" >&2
        exit 66
    }
done

actionlint_version=1.7.7
actionlint_image="rhysd/actionlint:${actionlint_version}@sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9"

docker run --rm -v "$(pwd):/repo:ro" -w /repo "$actionlint_image" -- "$@"
