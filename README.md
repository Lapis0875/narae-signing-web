# Narae Signing Web

Runtime contract: Java 21, Node.js 22, PostgreSQL 16, and Docker Compose v2.

## Commands

```sh
cd frontend && npm ci && npm run typecheck && npm run test && npm run build
cd backend && ./gradlew check
./scripts/test-plan-evidence.sh
./scripts/scan-tracked-secrets.sh --commit HEAD
```

`frontend` is a Vite React TypeScript shell. `backend` is a Spring Boot modular-monolith root under `com.naraesigning`. Product behavior is intentionally added by later plan todos.

## Verification

Run the command groups above from a clean managed worktree.
