# Narae Signing Web

Runtime contract: Java 21, Node.js 22, PostgreSQL 16, and Docker Compose v2.

## Local CI

```sh
(cd frontend && npm ci && npx playwright install chromium webkit && npm exec biome lint . && npm exec -- tsc -b --pretty false && npm run test && npm run build && npx playwright test --project=chromium --project=webkit --grep '@route-shell')
(cd backend && ./gradlew check integrationTest)

ci_root=$(mktemp -d)
mkdir -p "$ci_root/secrets"
dd if=/dev/zero of="$ci_root/secrets/master.key" bs=32 count=1 status=none
export POSTGRES_DB=ci_database POSTGRES_USER=ci_user POSTGRES_PASSWORD=ci-generated-postgres-password
export MINIO_ROOT_USER=ci-generated-user MINIO_ROOT_PASSWORD=ci-generated-minio-password
export APP_MINIO_BUCKET=ci-generated-bucket APP_PUBLIC_ORIGIN=https://signing.example.invalid
export APP_CRYPTO_KEY_VERSION=1 NARAE_DATA_ROOT="$ci_root" FRONTEND_PORT=8080
docker compose -p narae-ci-local -f infra/compose/compose.yml config --quiet
COMPOSE_FILE=infra/compose/compose.yml COMPOSE_PROJECT_NAME=narae-ci-local ./scripts/test-compose-resources.sh
docker compose -p narae-ci-local -f infra/compose/compose.yml down --volumes --remove-orphans
rm -rf "$ci_root"

./scripts/verify-workflows.sh .github/workflows/ci.yml
./scripts/test-plan-evidence.sh
./scripts/scan-tracked-secrets.sh --commit HEAD
```

CI uses `npm exec biome lint .` for semantic linting. `npm run lint` also checks legacy formatting and currently reports pre-existing formatting drift under the locked Biome version.

`frontend` is a Vite React TypeScript shell. `backend` is a Spring Boot modular-monolith root under `com.naraesigning`. Product behavior is intentionally added by later plan todos.

보드 서명색 API 계약, V6 스키마 전환, 단일 릴리스와 같은 시점 복구 절차는 [보드 서명색 단일 릴리스·복구 절차](docs/BOARD_INK_COLOR_ROLLOUT.md)를 따른다.

## Verification

Run the command groups above from a clean managed worktree.
