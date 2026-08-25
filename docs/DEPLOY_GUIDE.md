# 나래 서명 MVP 빠른 배포 가이드

이 문서는 현재 소스 트리의 Docker Compose 스택을 한 대의 서버에 배포하고, HTTPS 주소에서 관리자·공개 서명 흐름을 시험하는 가장 짧은 절차다. 대상은 테스트·스테이징 또는 별도로 보호된 MVP 운영 환경이다.

이 방식은 소스에서 이미지를 빌드한다. CI 기반의 immutable release나 Portainer 운영 절차는 이 문서의 범위가 아니다.

Proxmox VE에서 새 LXC를 만드는 경우에는 먼저 [Proxmox LXC 배포 가이드](PROXMOX_LXC_DEPLOY_GUIDE.md)를 완료한다.

## 1. 먼저 확인할 것

- Docker Engine과 Docker Compose v2가 설치되어 있어야 한다.
- 호스트에는 최소 **8 vCPU, 16 GiB RAM, 120 GiB 이상의 영속 디스크**가 필요하다.
- 이 Compose 파일은 프로젝트 이름과 내부 서브넷이 고정되어 있으므로, 같은 Docker 호스트에서 다른 나래 서명 Compose 스택과 병렬로 실행하지 않는다.
- 관리자 세션과 CSRF 쿠키는 `Secure`다. 관리자 로그인과 실제 서명 시험은 반드시 **HTTPS** 공개 주소에서만 한다.
- 호스트의 swap 사용량이 높거나 Docker 서비스가 `unhealthy`·`restarting` 상태이면 기동을 반복하지 말고 먼저 호스트 문제를 해결한다.

공개 서비스는 리버스 프록시가 TLS를 종료하고 frontend 포트만 연결해야 한다. backend, PostgreSQL, MinIO는 외부에 직접 공개하면 안 된다.

## 2. 빠른 시작 순서

아래의 `<PUBLIC_DOMAIN>`은 실제 공개 도메인으로, `<REPOSITORY_ROOT>`는 이 저장소의 최상위 경로로 바꾼다.

```sh
cd <REPOSITORY_ROOT>

# 1) 영속 데이터와 암호화 키를 준비한다.
sudo install -d -m 700 /srv/narae-signing/{postgres,minio,secrets}
sudo sh -c 'umask 077; dd if=/dev/urandom of=/srv/narae-signing/secrets/master.key bs=32 count=1 status=none'
sudo chown 10001:10001 /srv/narae-signing/secrets/master.key
sudo chmod 400 /srv/narae-signing/secrets/master.key
sudo wc -c /srv/narae-signing/secrets/master.key

# 2) 환경 파일을 저장소 밖으로 노출하지 않도록 만들고 채운다.
cp infra/compose/.env.example infra/compose/.env
chmod 600 infra/compose/.env
$EDITOR infra/compose/.env

# 3) 값 누락을 먼저 검증한다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml config --quiet

# 4) 메모리 급증을 줄이기 위해 이미지는 순서대로 빌드한다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build frontend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build backend

# 5) 서비스를 시작하고 healthcheck 완료까지 기다린다.
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml ps
```

`wc -c`의 결과는 정확히 `32`여야 한다. 현재 backend 컨테이너는 UID `10001`로 실행되므로 Linux Docker 호스트에서는 key 파일을 이 UID만 읽을 수 있게 설정한다. host root는 계속 읽을 수 있지만, 다른 일반 사용자는 읽을 수 없다. rootless Docker나 UID 매핑을 바꾼 런타임은 실제 backend 실행 UID에 맞춘다.

master key는 이미 데이터가 생긴 뒤에 다시 만들거나 바꾸면 안 된다. 기존 암호화 데이터를 읽지 못할 수 있다.

`.env`와 master key는 Git, 채팅, 스크린샷, CI 로그에 올리지 않는다.

## 3. `.env` 구성

기본 틀은 [`.env.example`](../infra/compose/.env.example)다. 복사한 `infra/compose/.env`에 다음처럼 **자신의 값**을 입력한다. 아래의 꺾쇠괄호 값은 그대로 사용하지 않는다.

```dotenv
POSTGRES_DB=narae_signing
POSTGRES_USER=narae_app
POSTGRES_PASSWORD=<새로_생성한_긴_무작위_비밀번호>
MINIO_ROOT_USER=narae-minio
MINIO_ROOT_PASSWORD=<새로_생성한_긴_무작위_비밀번호>
APP_MINIO_BUCKET=narae-signatures
APP_PUBLIC_ORIGIN=https://<PUBLIC_DOMAIN>
APP_CRYPTO_KEY_VERSION=1
NARAE_DATA_ROOT=/srv/narae-signing
FRONTEND_PORT=8080
```

비밀번호는 서로 다른 무작위 값으로 만든다. 예를 들어 각 비밀번호마다 아래 명령을 한 번씩 실행하고, 출력값을 안전하게 `.env`에만 붙여 넣는다.

```sh
openssl rand -base64 36
```

| 변수 | 설정 방법 |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER` | 이 배포 전용 데이터베이스 이름과 계정 이름을 정한다. |
| `POSTGRES_PASSWORD` | PostgreSQL 계정의 새 무작위 비밀번호를 넣는다. |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` | MinIO 전용 접근 ID와 새 무작위 비밀번호를 넣는다. 다른 환경과 재사용하지 않는다. |
| `APP_MINIO_BUCKET` | 서명 파일을 둘 버킷 이름을 정한다. 예: `narae-signatures`. |
| `APP_PUBLIC_ORIGIN` | 사용자가 실제로 접속할 **정확한 HTTPS 주소**다. 예: `https://signing.example.com`. 프록시 내부 HTTP 주소나 `http://localhost`를 넣지 않는다. |
| `APP_CRYPTO_KEY_VERSION` | 새 환경은 `1`로 시작한다. 같은 데이터 루트에서는 키 교체 절차 없이 값을 바꾸지 않는다. |
| `NARAE_DATA_ROOT` | PostgreSQL, MinIO, master key를 보관할 절대 경로다. 위에서 만든 경로와 같아야 한다. |
| `FRONTEND_PORT` | 앱 호스트에서 frontend만 열 포트다. 기본값은 `8080`이다. |

## 4. HTTPS와 리버스 프록시

Compose는 frontend만 `${FRONTEND_PORT}`로 호스트에 공개한다. 외부 사용자는 이 포트에 직접 연결하지 않고, 리버스 프록시의 80/443을 통해 접속하게 한다.

배포 전에 다음을 맞춘다.

1. DNS의 `<PUBLIC_DOMAIN>`이 리버스 프록시를 가리키게 한다.
2. 리버스 프록시는 앱 호스트의 `FRONTEND_PORT`로만 내부 HTTP 연결을 한다.
3. 앱 호스트 방화벽에서 `FRONTEND_PORT`의 접속 원본을 리버스 프록시로 제한한다.
4. PostgreSQL `5432`, MinIO `9000`·`9001`, backend 포트는 WAN·다른 내부망에 열지 않는다.
5. 프록시가 `X-Narae-Client-IP`, `X-Forwarded-Proto: https`, `X-Forwarded-Host`를 신뢰 가능한 값으로 설정하고, 브라우저가 보낸 forwarded header는 전달하지 않게 한다.

Nginx Proxy Manager의 실제 설정값, TLS 인증서, SSE 시간 제한은 [리버스 프록시 설정 가이드](REVERSE_PROXY_SETUP_GUIDE.md)를 따른다. HTTPS가 준비되기 전에는 로그인 시험을 시작하지 않는다.

## 5. 기동 확인

먼저 앱 호스트에서 frontend를 통해 backend health를 확인한다.

```sh
curl -fsS http://127.0.0.1:8080/health
```

`FRONTEND_PORT`를 바꿨다면 `8080` 대신 그 값을 사용한다. 기대 결과는 HTTP 200과 다음 상태다.

```json
{"status":"UP"}
```

그 다음 외부 또는 프록시가 있는 네트워크에서 공개 HTTPS 주소를 확인한다.

```sh
curl -fsS https://<PUBLIC_DOMAIN>/health
```

`docker compose ... ps`에서는 `frontend`, `backend`, `postgres`, `minio`가 `healthy`여야 한다. `minio-init`는 버킷 생성 후 `exited (0)`으로 끝나는 것이 정상이다.

backend가 정상 기동한 뒤에는 key 경로를 읽을 수 있는지만 값 노출 없이 확인할 수 있다.

```sh
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml exec backend sh -c 'test -r /run/secrets/master.key && echo readable'
```

브라우저에서는 `https://<PUBLIC_DOMAIN>`으로만 접속해 로그인한다. `http://localhost:8080`은 health 확인에는 쓸 수 있지만 Secure 쿠키가 필요한 로그인·서명 시험에는 쓰지 않는다.

## 6. 초기 관리자와 기능 확인

공개 회원가입은 제공하지 않으며, Compose도 관리자 계정을 자동으로 만들지 않는다. 초기 테스트 관리자는 운영자가 별도 일회성 관리자 명령으로 생성해야 한다.

- 명령의 입력 형태는 `create-admin <email>`이며, 비밀번호는 대화형 프롬프트로만 입력한다.
- 비밀번호를 명령 인자, 환경 변수, SQL, 로그에 넣지 않는다.
- 초기 계정 생성 경로가 준비되지 않았다면 로그인 수동 테스트를 시작할 수 없다. 직접 DB 삽입 대신 승인된 운영 절차를 사용한다.

계정이 준비되면 [수동 테스트 가이드](MANUAL_TEST_GUIDE.md)의 관리자 로그인, 보드 생성, 공개 서명, 전체보기 순서로 실제 HTTPS 흐름을 확인한다. 기본 Playwright 화면 테스트만으로는 배포된 전체 스택의 HTTPS·프록시·세션 동작이 검증되지 않는다.

## 7. 자주 막히는 지점

| 증상 | 먼저 확인할 것 |
| --- | --- |
| `config --quiet` 실패 | `.env`의 필수 변수 누락, `NARAE_DATA_ROOT`가 절대 경로인지 확인한다. |
| 로그인 뒤 세션이 유지되지 않음 | HTTPS 여부, `APP_PUBLIC_ORIGIN`의 정확한 도메인, 프록시의 `X-Forwarded-Proto: https` 전달을 확인한다. |
| 프록시에서 502 | 프록시에서 앱 호스트 `FRONTEND_PORT`의 `/health`에 연결되는지, 앱 호스트 방화벽을 확인한다. |
| 화면은 보이지만 API가 실패 | `backend` health와 frontend의 `/api/` 프록시 연결을 확인한다. |
| `minio-init`가 성공 종료하지 않음 | MinIO 자격 증명, 버킷 이름, MinIO health를 확인한다. |
| 서비스가 반복 재시작하거나 느림 | Docker 상태, 디스크 여유, 메모리와 swap 압박을 확인한다. 같은 기동을 반복하지 않는다. |

진단이 필요하면 비밀값을 출력하지 않는 범위에서 최근 로그만 본다.

```sh
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml logs --tail=200 backend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml logs --tail=200 frontend
```

로그를 전달할 때는 비밀번호, 쿠키, CSRF 토큰, 공유 토큰, 실제 개인정보를 모두 제거한다.

## 8. 중지와 업데이트

스택을 중지해도 bind mount 데이터는 유지하려면 다음만 실행한다.

```sh
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml down
```

업데이트 전에는 데이터 루트와 master key의 복구 가능 여부를 조직의 승인된 백업 절차로 확인한다. 데이터 삭제, 볼륨 삭제, master key 재생성은 이 빠른 배포 절차에 포함하지 않는다.

추가 운영 제약은 [Compose 안내](../infra/compose/README.md)와 [리버스 프록시 설정 가이드](REVERSE_PROXY_SETUP_GUIDE.md)를 함께 확인한다.
