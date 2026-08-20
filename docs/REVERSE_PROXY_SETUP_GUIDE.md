# signing.lapis0875.com 리버스 프록시 설정 가이드

## 1. 목적과 적용 범위

이 문서는 ProxmoxVE 환경에서 Nginx Proxy Manager(NPM) LXC가 애플리케이션 LXC의 온라인 서명 보드 MVP를 공개 HTTPS 주소로 제공하도록 설정하는 절차다.

- 공개 주소: https://signing.lapis0875.com
- 외부 공개 대상: frontend Nginx 하나
- 내부 대상: Spring Boot API와 SSE, PostgreSQL, MinIO
- 직접 공개 금지: PostgreSQL, MinIO API, MinIO Console, Spring Boot 포트
- 기준 구현: docs/plan/IMPLEMENTATION_PLAN.md

이 문서는 현재 선택한 직접 DNS·NAT 방식만 다룬다. Cloudflare 프록시, Tailscale Funnel, 외부 로드밸런서는 별도 설계가 필요하다.

## 2. 목표 네트워크

~~~text
Internet
  │
  ├─ DNS A: signing.lapis0875.com → 공인 IPv4
  │
Router / NAT
  ├─ WAN TCP 80  → NPM_LXC_IP:80
  └─ WAN TCP 443 → NPM_LXC_IP:443

Nginx Proxy Manager LXC
  └─ Proxy Host signing.lapis0875.com
       └─ HTTP → APP_LXC_IP:8080

애플리케이션 LXC
  └─ frontend Nginx :8080
       ├─ /       → React 정적 파일
       ├─ /api/   → Spring Boot backend:8080
       └─ /health → Spring Boot health

Docker Compose 내부망
  ├─ backend
  ├─ postgres
  └─ minio
~~~

아래 값은 실제 값으로 바꾼다.

| 자리표시자 | 의미 |
| --- | --- |
| APP_LXC_IP | 애플리케이션 LXC의 내부 IPv4 주소 |
| NPM_LXC_IP | Nginx Proxy Manager LXC의 내부 IPv4 주소 |
| PUBLIC_IPV4 | 라우터의 인터넷 측 공인 IPv4 주소 |

## 3. 사전 조건

1. 애플리케이션 LXC에 Portainer Stack이 배포되어 있고, frontend 컨테이너가 APP_LXC_IP의 TCP 8080에서 응답한다.
2. NPM LXC와 애플리케이션 LXC는 서로 통신 가능한 내부망에 있다.
3. 라우터 또는 상위 방화벽에서 80과 443을 NPM LXC로 전달할 수 있다.
4. signing.lapis0875.com DNS를 수정할 권한이 있다.
5. NPM 관리 UI 자체는 강한 관리자 비밀번호와 별도 보호를 사용한다. 서명 보드용 Proxy Host에는 NPM Access List를 걸지 않는다. 공개 서명자가 접근해야 하기 때문이다.

NPM LXC에서 먼저 애플리케이션 연결을 확인한다.

~~~sh
curl -fsS http://APP_LXC_IP:8080/health
~~~

기대 결과는 HTTP 200과 비밀 정보가 없는 서비스 상태 응답이다. 이 단계가 실패하면 DNS나 인증서를 설정하기 전에 LXC 방화벽, frontend 컨테이너, 포트 바인딩을 해결한다.

## 4. 애플리케이션 LXC와 Compose 노출 규칙

### 4.1 frontend만 포트 공개

frontend Nginx 서비스만 애플리케이션 LXC의 8080으로 포트를 publish한다.

~~~text
APP_LXC_IP:8080 → frontend:80
~~~

다음 포트는 host에 publish하지 않는다.

~~~text
PostgreSQL 5432
MinIO API 9000
MinIO Console 9001
Spring Boot backend 8080
~~~

애플리케이션 LXC 방화벽은 TCP 8080의 원본을 NPM_LXC_IP로 제한한다. NPM과 앱이 같은 신뢰 가능한 내부망에 있어도, 인터넷·다른 VLAN에서 8080을 직접 열지 않는다.

### 4.2 frontend Nginx 구성

frontend 컨테이너의 Nginx는 React SPA를 제공하고 API와 SSE를 backend 서비스로 전달한다. 다음은 필요한 동작의 기준 예시다.

~~~nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location = /health {
        proxy_pass http://backend:8080/health;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
    }
}
~~~

중요 사항:

- NPM에서 frontend까지는 내부 HTTP일 수 있으므로, frontend가 X-Forwarded-Proto를 $scheme 값인 http로 덮어쓰면 안 된다. NPM이 전달한 https 값을 backend까지 보존한다.
- SSE는 WebSocket이 아니다. 다만 긴 HTTP 응답이므로 frontend와 NPM에서 proxy_buffering을 끄고 read timeout을 충분히 길게 둔다.
- backend는 server.forward-headers-strategy=native 설정으로 전달된 원본 HTTPS 정보를 처리한다. 이 설정이 없으면 Secure 쿠키·리다이렉트가 잘못 동작할 수 있다.

## 5. DNS와 라우터 설정

### 5.1 DNS

DNS 제공자에서 다음 A 레코드를 만든다.

| 이름 | 유형 | 값 |
| --- | --- | --- |
| signing.lapis0875.com | A | PUBLIC_IPV4 |

- IPv6 외부 연결을 실제로 설정·검증한 경우에만 AAAA 레코드를 추가한다.
- 공인 IP가 바뀌는 회선이면 DNS 제공자의 API 또는 DDNS로 A 레코드를 갱신해야 한다. 갱신하지 않으면 인증서 갱신과 행사 접속이 실패한다.
- DNS 전파 뒤 외부 네트워크에서 다음을 확인한다.

~~~sh
dig +short signing.lapis0875.com A
~~~

### 5.2 NAT와 방화벽

라우터에서 다음 TCP 포트 전달을 만든다.

| WAN 포트 | 대상 | 대상 포트 | 용도 |
| --- | --- | --- | --- |
| 80 | NPM_LXC_IP | 80 | Let’s Encrypt HTTP 검증과 HTTP→HTTPS 리다이렉트 |
| 443 | NPM_LXC_IP | 443 | 실제 HTTPS 서비스 |

- APP_LXC_IP:8080, PostgreSQL, MinIO 포트로 직접 전달하는 규칙은 만들지 않는다.
- ISP가 80/443 인바운드를 막거나 CGNAT를 쓰면 이 방식은 동작하지 않는다. 먼저 공인 IP·포트 개방 가능 여부를 확인하고, 불가능하면 별도 공개 경로를 결정한다.

## 6. Nginx Proxy Manager Proxy Host 생성

NPM 관리 화면에서 Hosts → Proxy Hosts → Add Proxy Host를 연다.

### 6.1 Details 탭

| 항목 | 값 |
| --- | --- |
| Domain Names | signing.lapis0875.com |
| Scheme | http |
| Forward Hostname / IP | APP_LXC_IP |
| Forward Port | 8080 |
| Cache Assets | Off |
| Block Common Exploits | On |
| Websockets Support | On |

Websockets Support는 SSE에 필수는 아니지만, 이 Proxy Host에서 켜도 문제없다. SSE 동작은 아래 Advanced 설정의 버퍼링·시간 제한이 결정한다.

### 6.2 SSL 탭

1. Request a new SSL Certificate를 선택한다.
2. Let’s Encrypt 약관에 동의하고 인증서 만료 알림용 이메일을 NPM에 입력한다.
3. Force SSL을 켠다.
4. HTTP/2 Support를 켠다.
5. HSTS는 첫 HTTPS 접속과 서명 흐름을 검증한 뒤 켠다. HSTS를 켠 뒤 잘못된 HTTPS 설정을 되돌리기 어렵기 때문이다.
6. 저장한다.

NPM이 인증서를 발급하지 못하면 DNS가 아직 다른 주소를 가리키거나 WAN 80이 NPM까지 전달되지 않는 경우가 가장 많다.

### 6.3 Advanced 탭

NPM이 생성한 Proxy Host server 블록에 다음 지시어를 추가한다.

~~~nginx
proxy_buffering off;
proxy_request_buffering off;
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
~~~

이 설정은 SSE가 중간 프록시 버퍼에 쌓여 관리자 전체보기 갱신이 늦어지는 일을 막는다.

## 7. 배포 전 보안 점검

- frontend Nginx만 APP_LXC_IP:8080으로 노출되어 있는지 확인한다.
- 5432, 9000, 9001, backend 내부 포트가 WAN·다른 내부망에서 열리지 않았는지 확인한다.
- NPM은 signing.lapis0875.com 하나만 앱 LXC로 보낸다. MinIO Console용 별도 Proxy Host는 만들지 않는다.
- NPM과 Portainer의 관리 UI는 공개 서명 도메인과 분리한다.
- 앱의 master key, PostgreSQL·MinIO 비밀번호, GHCR 토큰, Portainer webhook은 NPM 설정의 Advanced 탭이나 Git 저장소에 넣지 않는다.
- NPM에서 전달되는 X-Forwarded-Proto가 backend까지 보존되는지 확인한다. Secure·HttpOnly 관리자 세션 쿠키가 HTTPS에서만 전달되어야 한다.

## 8. 외부 검증 절차

### 8.1 HTTP와 TLS

외부 네트워크에서 실행한다.

~~~sh
curl -I http://signing.lapis0875.com
curl -I https://signing.lapis0875.com
curl -fsS https://signing.lapis0875.com/health
~~~

기대 결과:

- 첫 요청은 HTTPS 주소로 리다이렉트된다.
- 두 번째 요청은 유효한 Let’s Encrypt 인증서와 HTTP 200을 받는다.
- health 응답에는 상태만 있고 DB 주소·키·컨테이너 세부 정보가 없다.

### 8.2 브라우저 행사 흐름

1. https://signing.lapis0875.com 에서 관리자 로그인한다.
2. Secure, HttpOnly 관리자 세션 쿠키가 생성되고 HTTP 주소에서는 전달되지 않는지 브라우저 개발자 도구로 확인한다.
3. 보드를 열고 QR 또는 공유 링크를 iPad Safari와 Android Chrome에서 연다.
4. 명단 정보 식별·서명 제출 뒤 관리자 편집 화면과 전체보기의 SSE 갱신을 확인한다.
5. 전체보기를 최소 10분 열어 두고 새 제출이 즉시 반영되는지 확인한다.
6. 링크를 재발급하고, 이전 탭의 서명 제출이 일반 무효 안내를 받는지 확인한다.
7. 보드를 마감하고 final.png 다운로드를 확인한다.

### 8.3 배포 뒤 점검

- GitHub Actions가 선택한 vX.Y.Z 이미지 태그와 Portainer Stack의 실제 image tag가 일치하는지 확인한다.
- backend health가 UP이고 Flyway 오류가 없는지 확인한다.
- PostgreSQL과 MinIO 볼륨이 /srv/narae-signing 아래 bind mount를 사용하는지 확인한다.
- 외부 데이터 백업은 아직 없다는 위험을 운영 기록에 남긴다.

## 9. 장애 진단

| 증상 | 우선 확인할 항목 |
| --- | --- |
| 인증서 발급 실패 | DNS A 레코드, WAN 80 전달, NPM 80 수신, CGNAT 여부 |
| 502 Bad Gateway | NPM→APP_LXC_IP:8080 연결, LXC 방화벽, frontend 컨테이너 health |
| HTTPS 리다이렉트 반복 | Force SSL, X-Forwarded-Proto 보존, Spring forward headers 설정 |
| 로그인 뒤 쿠키가 없음 | HTTPS 인증서, Secure 속성, 같은 공개 origin, 서버 시간 |
| 전체보기 갱신이 늦음 | frontend·NPM proxy_buffering off, 3600초 timeout, backend SSE 연결 |
| 정적 화면은 보이지만 API 실패 | frontend Nginx의 /api proxy_pass, backend health, Compose 내부 DNS backend |
| MinIO·PostgreSQL이 외부에서 보임 | Compose ports, LXC 방화벽, 라우터 포트 전달 규칙을 즉시 점검 |

## 10. 변경·롤백 원칙

- Proxy Host 설정 변경 전 NPM 화면의 현재 값을 기록한다.
- 인증서·도메인 문제를 해결할 때 APP_LXC_IP나 내부 포트 공개 범위를 넓히지 않는다.
- 애플리케이션 롤백은 이전 검증 GHCR 태그를 Portainer에 다시 배포해 수행한다. NPM Proxy Host는 같은 도메인·대상을 유지한다.
- NPM이 Let’s Encrypt 인증서를 자동 갱신할 수 있도록 80/443 전달 규칙과 DNS A 레코드를 지속적으로 유지한다.
