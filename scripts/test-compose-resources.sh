#!/bin/sh
set -eu

compose_file=${COMPOSE_FILE:-infra/compose/compose.yml}
project=${COMPOSE_PROJECT_NAME:-narae-signing}
expected_frontend='250000000 268435456 unless-stopped'
expected_backend='2500000000 5368709120 unless-stopped'
expected_postgres='500000000 1073741824 unless-stopped'
expected_minio='500000000 1073741824 unless-stopped'
total=0

for service in frontend backend postgres minio; do
    block=$(awk -v heading="  $service:" '
        $0 == heading { found = 1; next }
        found && /^  [a-zA-Z0-9_-]+:/ { exit }
        found { print }
    ' "$compose_file")
    printf '%s\n' "$block" | grep -Eq '^    cpus: ' || { echo "FAIL: $service lacks top-level cpus" >&2; exit 1; }
    printf '%s\n' "$block" | grep -Eq '^    mem_limit: ' || { echo "FAIL: $service lacks top-level mem_limit" >&2; exit 1; }
    printf '%s\n' "$block" | grep -Eq '^    restart: unless-stopped$' || { echo "FAIL: $service lacks restart: unless-stopped" >&2; exit 1; }
done

docker compose -p "$project" -f "$compose_file" up -d --wait >/dev/null

for service in frontend backend postgres minio; do
    id=$(docker compose -p "$project" -f "$compose_file" ps -q "$service")
    [ -n "$id" ] || { echo "FAIL: $service is not running" >&2; exit 1; }
    actual=$(docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.HostConfig.RestartPolicy.Name}}' "$id")
    eval "expected=\$expected_$service"
    [ "$actual" = "$expected" ] || { echo "FAIL: $service expected '$expected', got '$actual'" >&2; exit 1; }
    memory=$(printf '%s\n' "$actual" | awk '{print $2}')
    total=$((total + memory))
    echo "PASS: $service $actual"
done

[ "$total" -le 8589934592 ] || { echo "FAIL: total memory $total exceeds 8589934592" >&2; exit 1; }
echo "PASS: total memory $total <= 8589934592"
