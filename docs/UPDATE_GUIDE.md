# 나래 서명 업데이트 가이드

이미 Docker Compose로 배포된 서버에서 최신 `main`을 받아 재빌드·배포하는 절차다. 최초 배포와 `.env` 구성은 [빠른 배포 가이드](DEPLOY_GUIDE.md)를 따른다.

`infra/compose/.env`와 `/srv/narae-signing`의 PostgreSQL·MinIO 데이터, `master.key`는 유지한다. `docker compose down -v`나 master key 재생성은 업데이트 절차에 포함하지 않는다.

## 개별 빌드 명령

소스 수준에서 빌드만 확인하려면 저장소 최상위에서 실행한다. frontend는 Node.js 22, backend는 Java 21을 사용한다.

```sh
# frontend
cd frontend
npm ci
npm run build

# backend
cd ../backend
./gradlew bootJar --no-daemon
```

서버 배포에는 아래 Docker Compose 빌드를 사용하면 된다. Dockerfile이 위 빌드 과정을 컨테이너 안에서 수행하므로 서버에 Node.js나 Java를 별도로 설치할 필요는 없다.

## 서버 업데이트

아래 `<REPOSITORY_ROOT>`를 실제 저장소 경로(예: `/opt/narae-signing`)로 바꾼다. 빌드는 메모리 사용량을 줄이기 위해 frontend와 backend를 순서대로 실행한다.

```sh
cd <REPOSITORY_ROOT>

# 로컬 수정이 있으면 먼저 정리한다. 출력이 비어 있어야 한다.
git status --short

# 최신 main만 fast-forward로 받는다.
git pull --ff-only origin main

# 기존 .env 값이 모두 유효한지 확인한다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml config --quiet

# 변경된 이미지를 순서대로 다시 만든다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build frontend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build backend

# 변경된 서비스를 재생성하고 healthcheck 완료까지 기다린다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml ps

# 기본 포트 8080 기준 health 확인
curl -fsS http://127.0.0.1:8080/health
```

`git status --short`에 출력이 있거나 `git pull --ff-only`가 실패하면 강제 초기화하지 않는다. 먼저 해당 변경의 출처를 확인한다. `infra/compose/.env`는 Git에 포함되지 않으므로 유지한 채로 업데이트한다. 이 버전에서는 `APP_SIGNER_SESSION_MAXIMUM_LIFETIME=PT2H`를 추가한다.

정상이면 `frontend`, `backend`, `postgres`, `minio`가 `healthy`이고 health 응답은 `{"status":"UP"}`이다. `FRONTEND_PORT`를 바꿨다면 마지막 `curl`의 `8080`도 같은 포트로 바꾼다.

## 실패 시 확인

민감정보를 출력하지 않는 범위에서 최근 로그만 확인한다.

```sh
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml logs --tail=200 backend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml logs --tail=200 frontend
```

외부 확인은 `https://<PUBLIC_DOMAIN>/health`와 관리자 로그인으로 진행한다. HTTPS·리버스 프록시 설정은 [빠른 배포 가이드](DEPLOY_GUIDE.md)를 따른다.
