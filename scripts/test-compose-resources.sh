#!/bin/sh
set -eu

production_compose_file=${PRODUCTION_COMPOSE_FILE:-${COMPOSE_FILE:-infra/compose/compose.yml}}
ci_compose_file=${CI_COMPOSE_FILE:-}
project=${COMPOSE_PROJECT_NAME:-narae-signing}
expected_frontend='250000000 268435456 unless-stopped'
expected_backend='2500000000 5368709120 unless-stopped'
expected_postgres='500000000 1073741824 unless-stopped'
expected_minio='500000000 1073741824 unless-stopped'
total=0

compose() {
    if [ -n "$ci_compose_file" ]; then
        docker compose -p "$project" -f "$production_compose_file" -f "$ci_compose_file" "$@"
    else
        docker compose -p "$project" -f "$production_compose_file" "$@"
    fi
}

validate_production_model() {
    docker compose -p "$project" -f "$production_compose_file" config --format json |
        ruby -rjson -e '
            expected = {
              "frontend" => [0.25, 268435456],
              "backend" => [2.5, 5368709120],
              "postgres" => [0.5, 1073741824],
              "minio" => [0.5, 1073741824]
            }
            services = JSON.parse(STDIN.read).fetch("services")
            expected.each do |name, (cpus, memory)|
              service = services.fetch(name)
              actual = [service.fetch("cpus").to_f, service.fetch("mem_limit").to_i, service.fetch("restart")]
              wanted = [cpus, memory, "unless-stopped"]
              abort "FAIL: production #{name} expected #{wanted.join(" ")}, got #{actual.join(" ")}" unless actual == wanted
              puts("PASS: production #{name} #{actual.join(" ")}")
            end
        '
}

validate_ci_cap() {
    [ -n "$ci_compose_file" ] || return 0
    compose config --format json |
        ruby -rjson -e '
            service = JSON.parse(STDIN.read).fetch("services").fetch("backend")
            actual = [service.fetch("cpus").to_f, service.fetch("mem_limit").to_i, service.fetch("restart")]
            wanted = [2.0, 5368709120, "unless-stopped"]
            abort "FAIL: CI backend expected #{wanted.join(" ")}, got #{actual.join(" ")}" unless actual == wanted
            puts("PASS: CI backend #{actual.join(" ")}")
        '
    expected_backend='2000000000 5368709120 unless-stopped'
}

[ -f "$production_compose_file" ] || { echo "FAIL: production Compose file not found: $production_compose_file" >&2; exit 1; }
[ -z "$ci_compose_file" ] || [ -f "$ci_compose_file" ] || { echo "FAIL: CI Compose file not found: $ci_compose_file" >&2; exit 1; }

validate_production_model
validate_ci_cap
compose config --quiet
compose up -d --wait >/dev/null

for service in frontend backend postgres minio; do
    id=$(compose ps -q "$service")
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
