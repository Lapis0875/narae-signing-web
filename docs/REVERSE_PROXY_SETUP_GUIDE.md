# Proxmox VE Nginx Proxy Manager에서 NaraeSign 연결하기

이 문서는 기존 **Nginx Proxy Manager(NPM) LXC**가 여러 LXC/VM을 이미 프록시하는 Proxmox VE 환경에서, NaraeSign을 별도 애플리케이션 LXC로 연결하는 WebUI 절차다. NPM에서 TLS를 종료하고, NaraeSign LXC에는 frontend 포트만 내부 HTTP로 연결한다.

이 절차는 직접 DNS와 pfSense NAT를 쓰는 구성을 전제로 한다. Cloudflare, 다른 L7 프록시, VPN 터널을 NPM 앞에 추가한 경우에는 클라이언트 IP 신뢰 경계를 별도로 설계해야 한다.

## 1. 먼저 정할 값

아래 값은 예시다. 실제 네트워크 값으로 바꾸며, Docker 내부 주소인 `172.30.0.x`는 NPM에 넣지 않는다.

| 항목 | 예시 | 의미 |
| --- | --- | --- |
| `PUBLIC_DOMAIN` | `signing.example.com` | 사용자가 브라우저에서 여는 공개 도메인 |
| `NPM_LXC_IP` | `192.168.10.10` | 기존 NPM LXC의 고정 LAN IP |
| `APP_LXC_IP` | `192.168.10.42` | NaraeSign LXC의 고정 LAN IP |
| `FRONTEND_PORT` | `8080` | NaraeSign Compose가 LXC에 공개하는 frontend 포트 |
| `PUBLIC_IPV4` | `<pfSense WAN 공인 IP>` | DNS A 레코드가 가리킬 공인 주소 |

NaraeSign의 `infra/compose/.env`에는 반드시 다음처럼 공개 도메인과 frontend 포트를 맞춘다.

```dotenv
APP_PUBLIC_ORIGIN=https://signing.example.com
FRONTEND_PORT=8080
```

`APP_PUBLIC_ORIGIN`은 NPM 내부 주소나 `http://APP_LXC_IP:8080`이 아니라, 브라우저가 실제로 사용하는 정확한 HTTPS origin이다. 끝에 경로나 포트를 붙이지 않는다.

## 2. 연결 구조와 포트

```text
Internet browser
  └─ HTTPS :443 / HTTP :80
       └─ pfSense WAN NAT
            └─ NPM LXC (NPM_LXC_IP)
                 └─ HTTP :8080
                      └─ NaraeSign LXC (APP_LXC_IP)
                           └─ frontend container :80
                                └─ private Docker network
                                     ├─ backend :8080
                                     ├─ PostgreSQL :5432
                                     └─ MinIO :9000
```

| 구간 | 프로토콜·포트 | 설정 원칙 |
| --- | --- | --- |
| 인터넷 → NPM | TCP `80`, `443` | pfSense는 이 두 포트만 NPM LXC로 전달한다. |
| NPM → NaraeSign | `http://APP_LXC_IP:FRONTEND_PORT` | NPM Proxy Host의 upstream이다. 내부 HTTP가 정상이며, 여기서 HTTPS를 다시 만들지 않는다. |
| NaraeSign LXC → Docker frontend | `FRONTEND_PORT:80` | Compose가 frontend만 publish한다. 기본값은 `8080:80`이다. |
| Docker 내부 | HTTP와 private network | backend, PostgreSQL, MinIO는 NPM·WAN에 직접 공개하지 않는다. |
| NPM 관리 화면 | 설치별 관리 포트, 흔히 `81` | 관리 LAN 또는 VPN에서만 접근한다. WAN NAT를 추가하지 않는다. |

NPM의 Forward Hostname/IP에는 `APP_LXC_IP`를 쓴다. Compose의 frontend 내부 IP `172.30.0.10`은 NPM LXC에서 도달할 수 없는 Docker 내부 주소다.

## 3. NPM을 열기 전 확인

### 3.1 NaraeSign LXC에서 Compose 상태 확인

NaraeSign LXC에서 frontend가 정상인지 먼저 확인한다.

```sh
cd /opt/narae-signing
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml ps
curl -fsS http://127.0.0.1:8080/health
```

`frontend`, `backend`, `postgres`, `minio`는 `healthy`여야 하고, health 응답은 `{"status":"UP"}`이어야 한다.

### 3.2 NPM LXC에서 upstream 연결 확인

NPM LXC 쉘에서 NPM을 거치지 않은 내부 연결을 확인한다.

```sh
curl -fsS http://APP_LXC_IP:FRONTEND_PORT/health
```

이 명령이 실패하면 NPM WebUI를 설정하지 않는다. NaraeSign LXC IP, `FRONTEND_PORT`, Compose health, Proxmox 방화벽을 먼저 고친다.

### 3.3 Proxmox·pfSense 방화벽

NaraeSign LXC의 Proxmox WebUI에서 다음을 확인한다.

1. **CT → Options → Firewall**을 켠다.
2. **CT → Firewall → Add**에서 inbound 허용 규칙을 만든다.

| 방향 | Action | Protocol | Source | Destination port | 설명 |
| --- | --- | --- | --- | --- | --- |
| IN | ACCEPT | TCP | `NPM_LXC_IP/32` | `FRONTEND_PORT` | NPM만 frontend로 연결 |

기본 inbound 정책이 `DROP`이 아니라면, 위 허용 규칙 뒤에 다른 원본의 `FRONTEND_PORT`를 막는 규칙을 추가한다. SSH 관리 규칙은 별도로 유지한다.

pfSense WebUI에서는 **Firewall → NAT → Port Forward**에서 이미 NPM으로 향하는 아래 두 규칙만 있으면 된다. NaraeSign LXC를 향한 별도 WAN NAT 규칙은 만들지 않는다.

| Interface | Protocol | Destination port | Redirect target | Redirect port |
| --- | --- | --- | --- | --- |
| WAN | TCP | `80` | `NPM_LXC_IP` | `80` |
| WAN | TCP | `443` | `NPM_LXC_IP` | `443` |

각 NAT 규칙에 연결된 WAN firewall rule도 존재해야 한다. PostgreSQL `5432`, MinIO `9000`/`9001`, backend 포트, `FRONTEND_PORT`를 WAN에서 직접 여는 규칙은 만들지 않는다.

## 4. DNS 준비

DNS 제공자에서 `PUBLIC_DOMAIN`의 A 레코드를 `PUBLIC_IPV4`로 만든다.

```text
signing.example.com.  A  <PUBLIC_IPV4>
```

IPv6를 실제로 NPM까지 구성하고 검증한 경우에만 AAAA 레코드를 추가한다. Let’s Encrypt HTTP-01 발급을 쓸 경우, 외부 인터넷에서 WAN `80`이 NPM `80`으로 도달해야 한다.

## 5. NPM WebUI: Proxy Host 만들기

NPM 관리 화면에서 **Hosts → Proxy Hosts → Add Proxy Host**를 연다.

### 5.1 Details 탭

| 화면 항목 | 입력값 | 이유 |
| --- | --- | --- |
| Domain Names | `PUBLIC_DOMAIN` | 예: `signing.example.com`. `https://`·경로·포트는 넣지 않는다. |
| Scheme | `http` | NPM과 NaraeSign frontend 사이의 내부 구간은 HTTP다. |
| Forward Hostname / IP | `APP_LXC_IP` | 예: `192.168.10.42` |
| Forward Port | `FRONTEND_PORT` | 기본값 `8080` |
| Cache Assets | Off | 로그인·API·공유 링크가 섞인 앱에 NPM cache를 추가하지 않는다. |
| Block Common Exploits | On | NPM 기본 보호를 유지한다. |
| Websockets Support | On | SSE에 WebSocket은 필수는 아니지만 HTTP/1.1 upstream 호환을 유지한다. |
| Access List | Publicly Accessible | 공개 서명 URL이 있으므로 NPM Access List로 전체 도메인을 잠그지 않는다. 관리자 보호는 앱 로그인으로 처리한다. |

저장 전 다음 탭도 모두 설정한다.

### 5.2 Custom Locations 탭: 필수 클라이언트 IP 헤더

**Custom Locations → Add Location**을 열고 root location 하나를 추가한다.

| 화면 항목 | 입력값 |
| --- | --- |
| Location | `/` |
| Scheme | `http` |
| Forward Hostname / IP | `APP_LXC_IP` |
| Forward Port | `FRONTEND_PORT` |
| Forward Path | 비워 둠 |

이 Custom Location의 **Advanced** 입력란에는 아래 한 줄만 넣는다.

```nginx
proxy_set_header X-Narae-Client-IP $remote_addr;
```

이 헤더는 NPM이 관측한 접속 IP를 frontend를 거쳐 backend로 전달한다. 관리자 로그인은 이 헤더가 없거나 IP 형식이 아니면 거부되므로 생략하면 안 된다.

중요한 점:

- Proxy Host의 상단 Advanced 탭에 `proxy_set_header X-Narae-Client-IP ...`만 넣어서는 충분하지 않다. NPM의 기본 root location이 location 수준에서 proxy header를 다시 선언해 상단 설정을 상속하지 않는다. NPM의 [Proxy Host 템플릿](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/backend/templates/proxy_host.conf)과 [Custom Location 템플릿](https://github.com/NginxProxyManager/nginx-proxy-manager/blob/develop/backend/templates/_location.conf)의 배치가 그 이유다.
- raw `location / { ... }` 블록을 Proxy Host Advanced 탭에 직접 붙여 넣지 않는다. NPM이 생성하는 root location과 충돌할 수 있다.
- `X-Forwarded-Proto`는 Custom Location Advanced에 직접 덮어쓰지 않는다. NPM은 TLS로 받은 요청의 scheme을 `https`로 자동 전달한다. NaraeSign frontend는 그 값을 backend까지 보존한다.
- 이 안내는 NPM이 직접 TLS를 종료하는 구조에만 맞는다. NPM 앞에 Cloudflare·또 다른 reverse proxy를 두면 `$remote_addr`가 실제 브라우저 IP가 아닐 수 있으므로, 그 프록시의 trusted IP 범위를 별도 설계하고 검증한다.

### 5.3 Proxy Host Advanced 탭: 업로드·SSE 설정

Proxy Host의 **Advanced** 탭에는 아래 설정을 추가한다.

```nginx
# NaraeSign 배경 이미지 업로드의 전체 HTTP request 상한: 52,500,000 bytes
client_max_body_size 52500000;

# 일반 API mutation은 완전한 request body를 받아 upstream으로 전달한다.
proxy_buffering on;
proxy_request_buffering on;

# SSE 연결을 최대 1시간 유지한다.
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
```

`client_max_body_size`는 NPM 바깥쪽 제한이다. frontend와 backend는 경로별로 더 작은 상한을 다시 적용하므로, NPM에서 `0`, `100m`, `2000m` 같은 더 큰 값으로 풀지 않는다.

| 기능 | 최대 전체 요청 크기 | 실제 경로 |
| --- | ---: | --- |
| 배경 이미지 업로드 | `52,500,000` bytes | `POST /api/v1/admin/boards/{id}/background` |
| 명단 CSV/XLSX import | `1065000` bytes | `POST /api/v1/admin/boards/{id}/roster/import` |
| 명단 JSON·서명 payload | `1048576` bytes | 명단 수정·서명 제출 경로 |
| 로그인·공개 식별 | `16384` bytes | 로그인·식별 경로 |
| 기타 API mutation | `65536` bytes | 그 외 비-GET API |

배경 이미지의 상한은 약 50 MB이며 multipart 경계도 전체 요청 크기에 포함된다. 따라서 파일 자체는 이 값보다 약간 작아야 한다. NPM과 frontend가 request body를 버퍼링하므로, NPM LXC의 Docker/임시 저장소에 동시 업로드 수보다 충분한 디스크 여유를 남긴다.

SSE는 WebSocket이 아니다. frontend가 SSE 응답에 `X-Accel-Buffering: no`를 넣어 NPM에서 해당 응답만 버퍼링하지 않게 한다. 위의 일반 `proxy_buffering on`과 `proxy_request_buffering on`은 이미지 업로드·로그인·서명 제출에 유지해야 한다.

### 5.4 SSL 탭

1. **SSL Certificate**에서 **Request a new SSL Certificate**를 선택한다.
2. Let’s Encrypt 이메일과 약관 동의를 입력한다.
3. **Force SSL**을 켠다.
4. **HTTP/2 Support**를 켠다.
5. 처음 배포할 때는 **HSTS**와 **HSTS Subdomains**를 끈다.
6. 저장한 뒤 로그인·서명·SSE가 모두 정상인 것을 확인한 경우에만 HSTS를 켠다.

HTTP-01 발급이 실패하면 Proxy Host 값을 바꾸기 전에 DNS A 레코드, WAN `80` NAT, NPM LXC의 `80` 수신, CGNAT 여부를 점검한다. WAN `80`을 열 수 없는 환경은 DNS challenge를 별도로 설계해야 한다.

## 6. 저장 뒤 확인할 NPM 화면 상태

**Hosts → Proxy Hosts** 목록에서 해당 host가 Online인지 확인한다. 오류가 나면 다음 순서로 확인한다.

1. NPM LXC에서 `curl http://APP_LXC_IP:FRONTEND_PORT/health`가 먼저 성공하는지 확인한다.
2. Proxy Host의 upstream이 `https`, Docker 내부 IP, backend 포트를 가리키지 않는지 확인한다.
3. Custom Location `/`이 있고 `X-Narae-Client-IP` 헤더 한 줄이 있는지 확인한다.
4. Proxy Host Advanced 설정에 `client_max_body_size 52500000;`와 3600초 timeout이 있는지 확인한다.
5. NPM 로그와 브라우저 Network 응답을 확인하되, 쿠키·공유 토큰·실제 개인정보는 로그나 스크린샷에 남기지 않는다.

## 7. 외부 검증

LAN 내부 주소가 아닌 외부 네트워크에서 다음을 실행한다.

```sh
curl -I http://<PUBLIC_DOMAIN>/health
curl -fsS https://<PUBLIC_DOMAIN>/health
```

기대 결과:

- HTTP 요청은 같은 host·path·query를 보존한 HTTPS 리다이렉트다.
- HTTPS health는 HTTP 200과 `{"status":"UP"}`을 반환한다.
- 브라우저는 `https://PUBLIC_DOMAIN`에서 관리자 로그인에 성공한다.

그 다음 실제 브라우저에서 다음을 검증한다.

1. 관리자 로그인 후 새로고침해도 세션이 유지된다.
2. 보드 배경 이미지 업로드가 성공하며, 한도를 넘은 파일은 `413 request_too_large`로 거부된다.
3. 공개 서명 URL을 별도 기기에서 열고 식별·서명을 제출한다.
4. 관리자 전체보기를 열어 둔 채 서명을 제출했을 때 새 서명이 즉시 반영된다. DevTools Network에서 SSE 응답이 `text/event-stream`이고 `X-Accel-Buffering: no`인지 확인한다.
5. 최소 10분 동안 전체보기를 열어 SSE가 90초 전후에 끊기지 않는지 확인한다.

## 8. 자주 발생하는 문제

| 증상 | 우선 확인할 원인 |
| --- | --- |
| NPM `502 Bad Gateway` | NPM LXC→`APP_LXC_IP:FRONTEND_PORT` 연결, Proxmox 방화벽, Compose health |
| 인증서 발급 실패 | DNS A 레코드, pfSense WAN `80` NAT, CGNAT, NPM `80` 수신 |
| 관리자 로그인에서 `INVALID_CLIENT_IP` 또는 400 | Custom Location `/`의 `X-Narae-Client-IP` 설정 누락·오타, NPM 앞의 추가 프록시 |
| 로그인 뒤 세션이 사라짐 | `APP_PUBLIC_ORIGIN`과 공개 도메인 불일치, Force SSL, HTTPS 인증서, `X-Forwarded-Proto` 전달 |
| 이미지 업로드가 즉시 413 | Proxy Host Advanced의 `client_max_body_size 52500000;` 누락, 실제 파일·multipart 요청이 상한 초과 |
| 작은 업로드만 실패 | endpoint별 frontend/backend 상한을 넘었는지 확인. NPM 상한을 무작정 키우지 않는다. |
| 전체보기 갱신이 늦거나 끊김 | 3600초 timeout 누락, NPM/상위 프록시 버퍼링, 브라우저 Network의 SSE 응답 확인 |
| 앱은 보이지만 API가 실패 | NPM이 frontend가 아닌 backend·MinIO·Docker 내부 IP로 향하는지, `/api/` frontend proxy가 정상인지 확인 |

## 9. 안전한 변경과 롤백

- Proxy Host를 변경하기 전 NPM WebUI에서 현재 Details, Custom Locations, Advanced, SSL 값을 캡처한다. 캡처에는 인증서 개인키·쿠키·토큰을 포함하지 않는다.
- 문제가 생기면 NaraeSign LXC의 공개 포트를 늘리지 말고, NPM Proxy Host를 직전 값으로 되돌린다.
- `5432`, `9000`, `9001`, backend 포트를 임시로 공개해서 502를 우회하지 않는다.
- Proxy Host를 제거하기보다 먼저 **Disable**하여 DNS·인증서·NAT와 분리해 진단한다.

## 10. 구현 근거

현재 Compose는 frontend만 `${FRONTEND_PORT:-8080}`으로 LXC에 publish하며, backend는 frontend Docker IP `172.30.0.10`에서 온 proxy header만 신뢰한다. frontend는 배경 업로드를 `52,500,000` bytes로 제한하고 SSE 응답에 버퍼링 해제 헤더를 붙인다. NPM의 Proxy Host와 Custom Location 설정은 이 계약을 그대로 보존해야 한다.

- [Compose 설정](../infra/compose/compose.yml)
- [frontend Nginx 설정](../infra/nginx/default.conf)
- [빠른 배포 가이드](DEPLOY_GUIDE.md)
- [Proxmox LXC 배포 가이드](PROXMOX_LXC_DEPLOY_GUIDE.md)
