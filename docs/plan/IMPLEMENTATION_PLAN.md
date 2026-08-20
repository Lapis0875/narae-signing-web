# 온라인 서명 보드 MVP 구현 계획서

## 0. 문서 지위

- 기준 문서: docs/plan/PRODUCT_PLAN.md
- 목적: OMO plan 작성 전에 사람이 검토할 수 있도록 MVP의 화면, 데이터, API, 운영, 검증 방식을 구체화한다.
- 범위: 제품 기획에서 확정한 기능을 하나의 배포 가능한 MVP로 구현한다. 기능을 여러 출시로 나누지 않는다.
- 디자인 경계: 화면 구조·컴포넌트·상호작용은 이 문서에서 정한다. 색상, 서체, 여백, 아이콘 등 시각 토큰은 추후 DESIGN.md를 받은 뒤 적용한다.
- 문서 변경: 이 문서의 계정 정책에 맞춰 PRODUCT_PLAN.md도 공개 가입·이메일 재설정 표현을 제거했다.

## 1. 확정된 구현 원칙

### 1.1 제품 및 운영 범위

- 행사 하나당 보드 하나, 보드당 참가자는 최대 50명이다. 실제 운영 목표는 P95 25명 이하이다.
- 관리자 한 명이 여러 보드를 소유한다. 공개 가입은 없고, 운영자가 계정을 생성·비밀번호 재설정한다.
- 관리자 세션은 로그인 시점부터 절대 12시간 후 만료한다. 만료 30분 전부터 재로그인 안내를 표시한다.
- 서명자는 계정 없이 비공개 링크 또는 QR 코드로 접속한다. iPadOS 16 이상 Safari와 Android 12 이상 Chrome의 세로·가로 태블릿을 지원한다. 스마트폰은 지원하지 않는다.
- 링크 재발급은 즉시 이전 링크와 해당 링크에서 발급된 서명자 세션을 무효화한다.
- 보드 삭제는 접근을 먼저 차단하고, 데이터·객체 삭제를 끝까지 재시도하는 영구 삭제다.
- 실제 행사 전에 외부 데이터 백업은 구축하지 않는다. 호스트·디스크 손실 시 데이터가 사라질 수 있는 위험을 수용한다. 백업은 MVP 완료 후 별도 범위다.

### 1.2 기술 선택

- 프런트엔드: Vite, React, TypeScript, Tailwind CSS, shadcn/ui
- 프런트엔드 데이터: React Router, TanStack Query, 컴포넌트 로컬 상태
- 백엔드: Java Spring Boot 모듈형 모놀리스, Spring MVC, Spring Security, Spring Data JPA/Hibernate, Flyway, Gradle Kotlin DSL
- 세션: PostgreSQL 기반 Spring Session JDBC, Secure HttpOnly SameSite 쿠키, CSRF 방어
- 실시간 갱신: REST 명령과 관리자 전용 SSE
- 저장소: PostgreSQL과 private MinIO
- 배포: Docker Compose를 Portainer Stack으로 배포하고, Nginx Proxy Manager가 HTTPS를 종료한다.
- CI/CD: GitHub Actions, private GHCR, 수동 태그 배포, Portainer webhook

### 1.3 미포함 또는 후속 범위

- 공개 관리자 가입, 이메일 인증, SMTP, 이메일 비밀번호 재설정
- 복수 관리자·소유권 이전·공개 전체보기
- 스마트폰 서명, 다국어, 법적 전자서명
- 외부 데이터 백업과 복구 자동화
- 비밀번호 5회 실패 시 관리자 알림. 현재 MVP에는 5회 실패 뒤 15분 차단만 넣고, 알림은 이 문서 확정 뒤 별도 계획으로 다룬다.
- 추후 DESIGN.md에 따른 시각 디자인 고도화

## 2. 목표 구조와 신뢰 경계

~~~text
참가자·관리자 브라우저
        │ HTTPS :443
        ▼
공유기/NAT ── Nginx Proxy Manager LXC
        │ HTTP, 내부망
        ▼
애플리케이션 LXC
  frontend Nginx :8080
        │ /api, SSE
        ▼
  Spring Boot backend
     ├── PostgreSQL
     └── private MinIO
~~~

- 인터넷에는 Nginx Proxy Manager의 80/443만 공개한다.
- 애플리케이션 LXC에서는 frontend Nginx 포트만 Nginx Proxy Manager LXC가 접근할 수 있게 한다.
- PostgreSQL과 MinIO API·Console 포트는 Compose 내부 네트워크에만 둔다.
- 프런트엔드는 항상 동일 출처인 https://signing.lapis0875.com 을 사용한다. 별도 CORS 정책이나 브라우저 토큰 저장소는 만들지 않는다.
- HTTPS 종료 지점은 Nginx Proxy Manager다. Spring Boot는 전달된 HTTPS 원본 정보를 신뢰하도록 프록시 헤더 처리를 설정한다.

## 3. 저장소와 배포 산출물 구조

~~~text
frontend/                         React 애플리케이션
backend/                          Spring Boot 애플리케이션
infra/
  compose/                        개발·운영 Compose 정의와 환경 변수 예시
  nginx/                          frontend Nginx 설정
  portainer/                      Portainer Stack 입력용 운영 정의
docs/
  plan/
    PRODUCT_PLAN.md
    IMPLEMENTATION_PLAN.md
  REVERSE_PROXY_SETUP_GUIDE.md
~~~

- frontend와 backend는 독립적으로 빌드하고 별도 컨테이너 이미지로 배포한다.
- 공통 API 타입을 위한 별도 패키지는 만들지 않는다. OpenAPI 또는 API 계약 테스트를 기준으로 경계를 유지한다.
- 비밀 값, 실제 IP, 비밀번호, 복구 키는 Git·이미지·문서에 넣지 않는다.

## 4. 화면, 경로, 컴포넌트

### 4.1 관리자 경로

| 경로 | 목적 | 핵심 구성 |
| --- | --- | --- |
| /login | 관리자 로그인 | 이메일·비밀번호 폼, 일반 오류, 남은 차단 시간 안내 |
| /boards | 보드 목록 | 제목, 상태, 생성일, 새 보드, 삭제된 보드는 표시하지 않음 |
| /boards/new | 새 보드 생성 | 제목 입력 뒤 편집 화면으로 이동 |
| /boards/:boardId/edit | 준비·운영 관리 | 상태 도구막대, 배경, 캔버스, 미배치 명단, 명단 편집, 공유, 운영 현황 |
| /boards/:boardId/full | 관리자 전체보기 | 배경과 제출 서명만 전체 화면으로 표시 |

관리자 화면의 공통 컴포넌트는 AppShell, 인증 가드, SessionExpiryNotice, 상태 배지, 확인 대화상자, 토스트, 오류 상태다.

편집 화면은 다음 컴포넌트로 구성한다.

- BoardToolbar: 제목, 상태 전환, 자동 저장 상태, 전체보기, 최종 PNG, 삭제
- CanvasViewport: 배경 레이어와 정규화 좌표의 서명 칸 오버레이
- SlotOverlay: 선택, 이동, 크기 조절, 투명·흰색 배경, 충돌 표시
- UnplacedRosterPanel: 미배치 사람 목록과 캔버스 배치 시작점
- RosterPanel: 직접 추가·수정·제거, 붙여 넣기·CSV/XLSX 가져오기, 제출 상태
- BackgroundPanel: 업로드, 일반 교체, 새 이미지 비율로 캔버스 맞추기
- SharePanel: 링크 복사, QR 표시, 링크 재발급 확인
- OperationPanel: 미제출·제출·미배정 필터와 해당 칸으로 이동

### 4.2 서명자 경로

| 경로 | 상태 | 핵심 구성 |
| --- | --- | --- |
| /sign/:shareToken | 설정 중 | 제목과 대기 안내 |
| /sign/:shareToken | 서명 진행, 미식별 | 소속사·직책·이름 입력, 정보 보관 안내 |
| /sign/:shareToken | 식별 성공 | 흰 서명 패드, 전체 지우기, 제출 |
| /sign/:shareToken | 완료·이미 제출·마감·무효 링크 | 다른 사람 정보를 드러내지 않는 단일 안내 화면 |
| /sign/:shareToken | 작은 화면 | 태블릿에서 이용하라는 지원 불가 안내 |

- IdentityForm은 소속사·직책의 공란을 허용하고, 원문 문자열을 바꾸지 않는다.
- SignaturePad는 네이티브 canvas와 Pointer Events를 사용한다. 서명 라이브러리는 추가하지 않는다.
- 서명 획은 검정 고정 굵기, 곡선 보정, 압력 무시로 처리한다.
- 현재 열린 페이지와 전송 실패 재시도 중에는 그린 획을 유지한다. 새로고침·탭 종료·세션 만료 뒤에는 초안을 복원하지 않는다.

### 4.3 전체보기

- 관리자 인증이 있는 별도 탭에서만 연다.
- 배경, 흰색 칸 배경, 제출된 획만 보인다.
- 이름·소속사·직책, 슬롯 테두리·선택 상태, 편집 제어, 운영 목록은 절대 보이지 않는다.
- SSE를 받으면 TanStack Query의 보드 조회를 무효화하고, 인증된 REST 조회 결과로 다시 그린다.

## 5. 프런트엔드 상태와 상호작용

### 5.1 데이터 소유권

- React Router는 URL과 화면 전환만 담당한다.
- TanStack Query는 로그인 상태, 보드 목록·상세, 명단, 슬롯, 공유 링크 상태처럼 서버가 기준인 데이터를 관리한다.
- 선택된 슬롯, 드래그 중 포인터 좌표, 열린 대화상자, 서명 패드의 아직 제출되지 않은 획은 컴포넌트 로컬 상태다.
- SSE는 상태 원본이 아니다. 이벤트 수신 후 해당 보드와 목록 쿼리를 무효화하고 REST로 전체 최신 스냅샷을 받는다.

### 5.2 자동 저장

- 슬롯 이동·크기 조절은 포인터를 놓은 때 한 번 저장한다. 드래그 중 매 프레임 요청하지 않는다.
- 명단 수정, 배경 교체, 칸 배경 변경, 상태 전환도 완료 시 즉시 저장한다.
- 저장 중·저장됨·실패 상태를 BoardToolbar에 표시한다.
- 저장 실패 시 서버 기준 데이터를 다시 불러오고, 실패한 동작을 다시 시도할 수 있게 한다. 보이지 않는 브라우저 임시 초안은 만들지 않는다.

### 5.3 캔버스와 슬롯

- 슬롯의 x, y, width, height는 캔버스 폭·높이에 대한 0~1 정규화 값으로 저장한다.
- 클라이언트는 이동·크기 조절 중 캔버스 밖 배치와 다른 슬롯과의 교차를 막는다.
- 서버도 동일한 정규화 사각형 충돌 검사를 수행한다. 클라이언트 검사는 사용자 경험, 서버 검사는 권한 경계다.
- 일반 배경 교체는 새 이미지를 기존 캔버스에 비율 유지로 모두 표시하고, 남는 영역은 흰색으로 둔다.
- 관리자가 새 이미지 비율로 캔버스 맞추기를 확인하면 캔버스 비율을 바꾸되, 정규화된 슬롯과 이미 제출된 획은 같은 상대 위치·크기를 유지한다.

### 5.4 서명 획 처리

- 패드는 화면상 좌표를 0~1 정규화 점 목록으로 바꾼다.
- 브라우저가 점을 단순화하고, 제출 전체는 최대 10,000점·1 MiB를 넘지 않게 한다.
- 서버는 점 개수·범위·숫자 유효성·요청 크기를 다시 검증한다.
- 획은 작성 패드의 비율을 기준으로 슬롯 안에 비율 유지·가운데 정렬한다. 빈 여백은 그대로 남긴다. 가로·세로 독립 확대나 잘라 내기는 하지 않는다.

### 5.5 명단 가져오기

- CSV/XLSX 파일은 프런트엔드가 행 데이터로 해석하고, 서버에는 JSON 행만 보낸다. 원본 파일은 보관하지 않는다.
- CSV/XLSX는 첫 시트 첫 행에 소속사, 직책, 이름 순서의 정확한 헤더가 있어야 한다.
- CSV는 UTF-8과 쉼표 구분만 허용한다. 다른 인코딩·구분자·추가 열·다른 시트는 거절한다.
- 붙여 넣기 데이터는 같은 헤더 행이 있으면 건너뛰고, 없으면 첫 행부터 데이터로 읽는다.
- 원본 입력은 1 MiB 이하, 반영 대상은 최대 50행이다.
- 서버가 이름 누락·정확 일치 중복·행 수·상태 제약을 전체 검사한다. 하나라도 실패하면 정상 행도 반영하지 않고 오류 행을 돌려준다.

## 6. 백엔드 모듈과 데이터 모델

### 6.1 모듈 경계

| 모듈 | 책임 |
| --- | --- |
| auth | 관리자 로그인·로그아웃·세션 만료·CSRF·로그인 차단 |
| adminops | create-admin, reset-password 운영 명령 |
| board | 보드 생성·상태 전환·소유권 확인·공유 링크·삭제 |
| roster | 명단 직접 편집·일괄 반영·정확 일치 식별값·상태 규칙 |
| layout | 슬롯 생성·배치·충돌 검증·정규화 좌표 |
| signing | 서명자 식별 세션·벡터 제출·첫 제출 승자 규칙 |
| media | 배경 검증·정규화·암호화 MinIO 저장·최종 PNG 렌더링 |
| realtime | 관리자 SSE 연결·이벤트 발행·재연결 지원 |
| crypto | 키 파생·AES-GCM 암복호화·HMAC 식별값·키 버전 처리 |
| ops | 헬스 체크·구조화 로그·삭제 객체 정리 재시도 |

모듈은 하나의 Spring Boot 프로세스와 하나의 PostgreSQL 데이터베이스를 사용한다. 별도 메시지 브로커, Redis, 워커 서비스, 공개 객체 URL은 만들지 않는다.

### 6.2 주요 테이블

| 테이블 | 핵심 내용 |
| --- | --- |
| admin_user | UUID, 이메일, BCrypt 비밀번호 해시, 활성 상태, 생성·수정 시각 |
| board | UUID, owner_id, 평문 제목, 상태, 캔버스 폭·높이, 배경 참조, 공유 링크 버전, 생성·수정 시각 |
| roster_entry | UUID, board_id, 암호화된 소속사·직책·이름 묶음, nonce, key_version, identity_hmac, 제출 상태 |
| signature_slot | UUID, roster_entry_id, 배치 상태, 정규화 사각형, 칸 배경, 암호화된 획 묶음, nonce, key_version, 제출 시각 |
| background_asset | UUID, board_id, 암호화 MinIO 객체 키, 원본 표시 폭·높이, MIME, 암호화 nonce·key_version |
| board_deletion_job | 접근 차단된 보드의 MinIO 객체 삭제 대상, 시도 횟수, 완료 시각, 마지막 오류 |
| spring_session 계열 | Spring Session JDBC가 관리하는 관리자·서명자 세션 |

- board에는 owner_id를 유지한다. 현재 단일 관리자 운영이더라도 모든 관리자 API는 소유권 범위를 확인한다.
- roster_entry의 identity_hmac에는 공백·대소문자를 바꾸지 않은 세 문자열과 board_id를 길이 접두어가 있는 UTF-8 바이트열로 넣는다.
- (board_id, identity_hmac) 유니크 제약으로 동일한 소속사·직책·이름 조합을 막는다.
- signature_slot은 roster_entry 하나에 하나만 존재한다. 제출 데이터는 슬롯에 한 번만 연결한다.
- 제출 완료·미제출·미배정 상태는 데이터베이스 상태에서 계산하거나 명시적으로 기록한다. 브라우저 상태를 신뢰하지 않는다.
- 자동 감사 로그 테이블은 만들지 않는다. 제품 범위에 감사 이력은 없다.

### 6.3 데이터 암호화

- 보호 대상: MinIO에 보관하는 배경 이미지 객체, PostgreSQL의 명단 원문과 서명 획.
- 평문 유지 대상: 보드 제목, 보드 상태, 소유자 참조, 캔버스·슬롯 좌표, 시간, 객체 키, 제출 여부, 키 버전, HMAC 식별값.
- 마스터 키는 32바이트 비밀 값이다. LXC의 root 전용 원본 파일을 Compose secret으로 backend에 읽기 전용으로만 주입한다.
- 동일 키의 암호화 키와 HMAC 키는 목적별로 분리해 파생한다. 새 값마다 새 nonce를 쓰는 AES-256-GCM을 사용한다.
- 각 암호문·객체 메타데이터는 key_version과 nonce를 가진다. 자동 키 교체는 하지 않으며, 향후 운영자용 수동 교체 절차가 기존 버전도 복호화할 수 있어야 한다.
- HMAC은 정확 일치 조회·유니크 검사에만 쓴다. HMAC만으로 원문을 복원할 수는 없지만, 키를 가진 서버는 후보 입력을 검사할 수 있다.
- 마스터 키가 없거나 길이·형식이 잘못되면 production 프로필은 시작하지 않는다. 새 키를 자동 생성해 기존 데이터를 읽지 못하게 만드는 동작은 금지한다.
- LXC 외부의 복구 사본은 데이터 백업이 아니라 키 복구용이다. 관리자 비밀번호 관리자 또는 동등한 오프라인 안전 저장소에 보관한다.
- 앱·LXC 호스트가 모두 침해되면 이 암호화만으로 보호되지 않는다. MinIO 볼륨이나 PostgreSQL 파일만 노출된 경우를 줄이는 방어다.

## 7. API와 상태 변경 계약

### 7.1 API 공통 규칙

- 모든 애플리케이션 API는 /api/v1 아래에 둔다.
- JSON 오류는 안정적인 code와 한국어 사용자 메시지를 분리한다. 로그에는 원문 명단·서명 획·공유 토큰·쿠키를 쓰지 않는다.
- 변경 요청은 CSRF 검사를 거친다. 공개 서명 API도 signer session과 CSRF 또는 엄격한 Origin 검사를 통과해야 한다.
- 관리자 API는 인증과 owner_id 범위 확인을 함께 한다.
- 상태를 바꾸는 요청은 현재 서버 상태를 트랜잭션 안에서 다시 검증한다.

### 7.2 인증 API

| 메서드·경로 | 동작 |
| --- | --- |
| POST /api/v1/auth/login | 이메일·비밀번호 검증, 관리자 세션 생성 |
| POST /api/v1/auth/logout | 관리자 세션 폐기 |
| GET /api/v1/auth/session | 로그인 여부와 절대 만료 시각 반환 |
| GET /api/v1/auth/csrf | SPA 요청용 CSRF 토큰 제공 |

- 관리자 인증 쿠키와 서명자 인증 쿠키는 서로 다른 이름·경로로 분리한다.
- 인증 쿠키는 Secure, HttpOnly, SameSite=Lax 속성을 사용한다. JavaScript가 읽는 XSRF 토큰은 인증 정보가 아니며 변경 요청 헤더에만 넣는다.
- 관리자는 로그인 생성 시각에서 12시간 뒤 무조건 만료한다. 활동이 있어도 연장하지 않는다.
- 프런트엔드는 expiresAt을 기준으로 30분 전부터 안내를 표시한다. 만료 뒤의 저장 요청은 로그인 필요 오류를 받고 로그인 화면으로 이동한다.
- 로그인은 이메일·IP 기준 연속 5회 실패 뒤 15분 차단한다. 성공·실패 모두 계정 존재 여부를 드러내지 않는 일반 메시지를 쓴다.
- 비밀번호는 최소 12자, 정기 변경 강제 없음, 비밀번호 관리자·긴 문장형 비밀번호 허용으로 운영한다.
- create-admin과 reset-password는 web API가 아닌 backend 일회성 운영 명령이다. 비밀번호를 로그·환경 변수·명령 인자에 평문으로 남기지 않고 표준 입력 프롬프트로 받는다.

### 7.3 관리자 보드 API

| 기능 | 대표 경로 |
| --- | --- |
| 목록·생성 | GET, POST /api/v1/admin/boards |
| 상세·제목 | GET, PATCH /api/v1/admin/boards/:boardId |
| 배경 업로드·교체 | POST /api/v1/admin/boards/:boardId/background |
| 새 이미지 비율 맞춤 | POST /api/v1/admin/boards/:boardId/canvas/adopt-background-ratio |
| 명단 조회·개별 편집 | GET, POST, PATCH, DELETE /api/v1/admin/boards/:boardId/roster |
| 명단 일괄 반영 | PUT /api/v1/admin/boards/:boardId/roster/import |
| 슬롯 배치·수정·삭제 | PATCH, DELETE /api/v1/admin/boards/:boardId/slots/:slotId |
| 서명 초기화 | POST /api/v1/admin/boards/:boardId/slots/:slotId/reset-signature |
| 상태 전환 | POST /api/v1/admin/boards/:boardId/open, close, reopen |
| 링크·QR | GET /api/v1/admin/boards/:boardId/share, POST /share/reissue |
| 전체보기 이벤트 | GET /api/v1/admin/boards/:boardId/events |
| 최종 PNG | GET /api/v1/admin/boards/:boardId/final.png |
| 영구 삭제 | DELETE /api/v1/admin/boards/:boardId |

- URL의 :boardId는 실제 구현에서 UUID 경로 변수다.
- open 요청은 제목, 최소 한 배치 슬롯, 모든 명단의 배치 완료를 다시 검사한다.
- 일괄 명단 교체는 설정 중에서만 허용한다. 서명 진행 중에는 한 명씩 추가·수정·제거 규칙만 허용한다.
- 완료 서명자의 정보 수정·삭제는 먼저 서명을 초기화한 뒤에만 허용한다.
- 슬롯 삭제는 현재 서명을 지우고 명단 사람을 미배정 상태로 돌린다.

### 7.4 공개 서명 API

| 기능 | 대표 경로 |
| --- | --- |
| 링크 상태 조회 | GET /api/v1/public/links/:shareToken |
| 명단 정보 식별 | POST /api/v1/public/links/:shareToken/identify |
| 서명 화면 데이터 | GET /api/v1/public/signing-session |
| 서명 제출 | POST /api/v1/public/signing-session/signature |

- 링크 상태 조회는 설정 중·서명 진행·마감·무효 상태에 필요한 최소 제목·안내만 준다.
- identify 요청은 정확한 명단 일치, 배치 완료, 미제출, 보드 서명 진행 상태를 모두 확인한다.
- 성공 시 서버가 board_id, slot_id, share_link_version만 가진 30분 signer session을 만든다. 명단 원문은 세션에 넣지 않는다.
- 실패 응답은 명단 존재 여부·다른 참가자 정보·슬롯 위치를 밝히지 않는다.
- submit 요청은 signer session의 링크 버전과 현재 슬롯 상태를 트랜잭션 안에서 확인한다.
- 관리자 초기화·삭제·재배정·링크 재발급이 먼저 일어났다면 제출은 안정적인 충돌 오류를 반환하고, 참가자에게 정보를 다시 입력하라고 안내한다.
- 한 슬롯의 첫 제출만 성공한다. 데이터베이스 유니크 제약 또는 조건부 갱신으로 동시 제출에서도 한 건만 저장한다.

## 8. 보드 생명주기, 실시간, 삭제

### 8.1 상태 전이

~~~text
설정 중 ── open ──> 서명 진행 ── close ──> 마감/보관
   ▲                     │                    │
   └──── reopen ──────────┴────────────────────┘

모든 상태 ── 확인 후 delete ──> 접근 차단 및 영구 삭제 처리
~~~

- 설정 중 링크는 대기 안내만 보인다.
- 서명 진행 중에는 명단·배경·슬롯을 수정할 수 있지만, 각 공개 제출은 서버의 최신 상태를 기준으로 검증한다.
- 마감 상태에서만 final.png를 요청할 수 있다. 다시 열었다가 재마감하면 다음 다운로드는 항상 현재 상태로 새로 렌더링한다.

### 8.2 SSE

- SSE는 관리자 편집 화면과 전체보기만 연결한다. 서명자의 작성 중 획은 전송하거나 방송하지 않는다.
- 커밋이 끝난 뒤 board-updated, signature-submitted, signature-reset, layout-updated, background-updated, board-state-updated, board-deleted 이벤트를 발행한다.
- 이벤트에는 이벤트 ID와 보드 ID만 넣고 명단 원문·서명 획은 넣지 않는다.
- 클라이언트는 연결이 끊기면 지수 백오프로 재연결하고 전체 스냅샷을 다시 읽는다. 이벤트 누락이 정합성을 깨지 않게 한다.
- 단일 backend 인스턴스에서는 프로세스 내 발행기로 충분하다. Redis·다중 인스턴스 브로드캐스트는 만들지 않는다.

### 8.3 삭제

1. 관리자 확인 요청에서 보드와 공유 링크를 먼저 사용할 수 없게 만든다.
2. signer session의 링크 버전 검증이 실패하도록 만든다.
3. 삭제 대상 MinIO 객체 키를 board_deletion_job에 기록한다.
4. 데이터베이스의 보드·명단·슬롯·암호문을 삭제하고, MinIO 객체를 삭제한다.
5. 객체 삭제가 실패하면 이미 공개 접근은 막힌 상태를 유지하고, ops 모듈이 재시도한다.

## 9. 이미지 처리와 최종 PNG

### 9.1 배경 업로드

- 허용 형식은 PNG와 JPG/JPEG다.
- 업로드는 최대 50 MiB, 디코드 후 가로·세로는 각각 최대 12,000px이다.
- 확장자·Content-Type만 믿지 않고 실제 바이트와 디코드 가능한 이미지인지 검사한다.
- JPEG 방향 메타데이터를 적용하고, 출력용 정규화 이미지에는 EXIF 등 불필요한 메타데이터를 남기지 않는다.
- 비정상 이미지, 디코드 폭탄, 크기 초과는 저장 전에 거절한다.
- 검증·정규화한 이미지 바이트를 AES-GCM으로 암호화해 private MinIO에 저장한다. 버킷은 공개 정책·직접 다운로드 URL을 가지지 않는다.

### 9.2 최종 PNG 렌더링

- Spring Boot가 private MinIO에서 배경을 복호화해 읽고, Java 2D 렌더러가 같은 정규화 좌표 규칙으로 배경·흰 슬롯·서명 획을 합성한다.
- 이름·소속사·직책, 편집 테두리, 선택 상태, QR, UI는 절대 렌더링하지 않는다.
- 배경이 없으면 흰색 1920×1080을 사용한다.
- 결과 PNG는 요청마다 메모리에서 생성해 응답으로 바로 보낸다. 결과 파일·개별 서명 파일·명단 파일을 저장하지 않는다.
- 서버 전체에 하나의 렌더링 세마포어만 둔다. 작업 중 다른 final.png 요청은 Retry-After와 함께 503을 반환하고 프런트엔드는 재시도 버튼을 보인다.
- 12,000×12,000 이미지는 압축 파일 크기와 무관하게 큰 메모리를 쓴다. backend는 대략 4~5 GiB, PostgreSQL·MinIO는 각각 약 1 GiB 한도를 기준으로 잡고, 애플리케이션 LXC는 4 vCPU·8 GiB RAM·80 GiB 영속 디스크 이상으로 준비한다.

## 10. 보안, 개인정보, 남용 방어

- 관리자 로그인과 공개 identify·서명 제출을 포함한 모든 상태 변경 요청은 세션과 CSRF를 필수로 검증하고, 보드·링크·슬롯 현재 상태도 함께 검증한다.
- Origin 검사는 브라우저 방어 심층화 수단일 뿐이며 CSRF나 인증·인가를 대신하지 않는다.
- 관리자 세션은 발급 시각부터 비연장 12시간, 서명자 세션은 최대 30분이다. 서명자 세션에는 board ID, slot ID, 공유 링크 버전, slot revision, 서버 발급 signature aspect ratio, issued-at만 저장하며 명단 원문과 좌표는 저장하지 않는다.
- `ADMIN_SESSION`과 `SIGNER_SESSION`은 경로가 분리된 Secure·HttpOnly·SameSite=Lax 쿠키이고, JavaScript가 읽는 `XSRF-TOKEN`은 인증 정보가 아니며 로그에 남기지 않는다.
- 공유 링크 토큰은 충분히 긴 무작위 값이며, DB에는 해시와 버전만 저장한다.
- public identify와 submit은 보드 링크와 IP를 함께 기준으로 인메모리 속도 제한한다. 같은 행사장 NAT 환경에서 P95 25명 서명 흐름을 막지 않는 수준으로 설정하고, 배포 전 실제 태블릿 시나리오로 조정한다.
- 특정 명단 행 자체를 실패 횟수로 잠그지 않는다. 다른 사람이 특정 참가자를 서명하지 못하게 만들 수 있기 때문이다.
- 관리자 로그인은 이메일·IP 기준 5회 실패 뒤 15분 차단한다. 관리자 실패 알림은 후속 범위다.
- MinIO·PostgreSQL 비밀값, master key, 복구 키, 공유 토큰, 명단 평문, 서명 획을 로그·예외 응답·분석 도구에 남기지 않는다.
- health 엔드포인트는 서비스 상태만 내보내며 키·스토리지 URL·DB 상세 정보를 내보내지 않는다.
- 스마트폰 차단은 지원 정책일 뿐 보안 경계가 아니다. user-agent만으로 권한을 판단하지 않는다.

## 11. Docker Compose와 운영 구성

### 11.1 서비스

| 서비스 | 책임 | 외부 노출 |
| --- | --- | --- |
| frontend | React 정적 파일 제공, /api·SSE를 backend로 프록시 | 애플리케이션 LXC 내부망 포트 8080만 |
| backend | REST, SSE, 렌더링, 암복호화, 운영 명령 | frontend Compose 네트워크만 |
| postgres | 관계형 데이터와 Spring Session | Compose 내부만 |
| minio | 암호화된 배경 객체 | Compose 내부만 |

- frontend Nginx는 SPA 경로를 index.html로 되돌리고, /api 요청과 SSE에서 버퍼링을 끈 채 backend로 보낸다.
- backend, postgres, minio는 internal Compose 네트워크를 쓴다.
- Compose에는 restart unless-stopped, healthcheck, 고정 이미지 태그, 리소스 한도를 둔다.
- PostgreSQL과 MinIO는 다음 bind mount 아래에 둔다.

~~~text
/srv/narae-signing/postgres
/srv/narae-signing/minio
/srv/narae-signing/secrets
~~~

- secrets 디렉터리에는 app master key 원본만 두며 Git·GHCR 이미지·MinIO에는 넣지 않는다.

### 11.2 필요한 환경 값

| 범주 | 예시 이름 | 저장 위치 |
| --- | --- | --- |
| DB | POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, SPRING_DATASOURCE_URL | Portainer 비밀 환경 값 |
| MinIO | MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, APP_MINIO_ENDPOINT, APP_MINIO_BUCKET | Portainer 비밀 환경 값 |
| 암호화 | APP_MASTER_KEY_FILE, APP_CRYPTO_KEY_VERSION | backend Compose secret와 일반 설정 |
| 세션·프록시 | APP_PUBLIC_ORIGIN, SERVER_FORWARD_HEADERS_STRATEGY | 일반 설정 |
| 배포 | GHCR_IMAGE, IMAGE_TAG | Portainer Stack 변수 |

- 실제 값은 .env.example에 넣지 않는다. 예시에는 값 없이 필요한 이름과 생성 방법만 둔다.
- 모든 Compose 기동 전 backend는 DB·MinIO 연결, 버킷 존재, master key 형식, Flyway 마이그레이션을 검사한다. 필수 조건이 실패하면 health가 UP이 되지 않는다.

## 12. CI/CD와 릴리스

### 12.1 GitHub Actions

1. pull request와 main 변경에서 frontend 타입 검사·단위 테스트·빌드, backend 테스트·빌드·Flyway 검증을 실행한다.
2. 운영 배포는 기존 Git 태그 vX.Y.Z를 명시해 수동 workflow_dispatch로만 시작한다.
3. workflow는 해당 태그의 frontend·backend 이미지를 private GHCR에 불변 태그로 올린다.
4. 이미지 push가 성공한 뒤 GitHub Secret에 보관한 Portainer webhook을 호출한다.
5. Portainer Stack은 지정된 태그를 pull하고 Compose 서비스를 교체한다.
6. 배포 뒤 /health와 로그인·공개 링크 기본 확인을 수행한다. 자동 프로덕션 배포는 하지 않는다.

### 12.2 마이그레이션과 롤백

- Flyway는 backend 시작 시 적용한다. 마이그레이션 실패 시 새 backend는 준비 상태가 되지 않는다.
- 마이그레이션은 이전 앱이 잠시 함께 떠도 깨지지 않는 순방향 호환 변경을 우선한다.
- 롤백은 이전 검증 태그를 수동 선택해 재배포한다. 이미 적용된 DB 구조를 자동으로 되돌리는 down migration은 만들지 않는다.

## 13. 구현 순서

### 단계 1: 저장소 골격과 운영 계약

- frontend, backend, infra 디렉터리와 Compose 개발 환경을 만든다.
- 환경 값 예시, bind mount, healthcheck, GitHub Actions 기본 검증을 만든다.
- Flyway 첫 스키마와 운영 명령의 실행 경로를 정한다.

완료 기준: 빈 서비스가 Compose에서 기동하고, frontend·backend·PostgreSQL·MinIO health를 확인할 수 있다.

### 단계 2: 관리자 인증과 암호화 기반

- admin_user, Spring Session JDBC, 절대 12시간 세션, CSRF, 로그인 차단을 구현한다.
- create-admin·reset-password 일회성 명령을 구현한다.
- master key 로딩, 목적별 키 파생, AES-GCM, HMAC, key_version 처리와 실패 시 시작 차단을 구현한다.

완료 기준: 공개 가입 없이 관리자만 로그인하고, 키 없이 production 시작이 실패하며, 암호화·복호화·HMAC 유니크 규칙이 검증된다.

### 단계 3: 보드·명단·레이아웃

- 보드 생명주기, 제목, 공유 링크 해시·재발급, 명단 직접 편집·일괄 반영을 구현한다.
- 슬롯 미배치·배치·충돌·초기화·삭제 규칙을 구현한다.
- 관리자 편집 화면, 자동 저장 표시, 명단 상태와 QR 표시를 구현한다.

완료 기준: 관리자 한 명이 50명 이하 명단을 준비하고, 모든 사람을 충돌 없이 배치한 뒤에만 보드를 열 수 있다.

### 단계 4: 배경·서명·실시간

- 이미지 검증·정규화·암호화 MinIO 저장과 배경 교체·비율 변경을 구현한다.
- public identify, signer session, native signature pad, 첫 제출 승자 규칙을 구현한다.
- SSE, 관리자 전체보기, 제출·초기화·레이아웃 갱신을 구현한다.

완료 기준: 태블릿 참가자가 한 번만 제출하고, 전체보기와 편집 화면에 완성 서명만 실시간 반영된다.

### 단계 5: 최종 결과·삭제·운영 배포

- Java 2D 최종 PNG, 단일 렌더링 제한, 503 재시도 UX를 구현한다.
- 접근 선차단과 durable object purge를 포함한 보드 삭제를 구현한다.
- GHCR 태그 배포, Portainer webhook, signing.lapis0875.com 프록시를 구성한다.

완료 기준: 마감 보드에서 현재 상태의 PNG를 받고, 삭제 뒤 이전 링크·객체 접근이 불가능하다.

### 단계 6: 자동 검증과 실제 기기 QA

- 아래 자동 테스트와 수동 QA를 수행한다.
- Playwright Chromium·WebKit, 실제 iPad Safari·Android Chrome으로 핵심 행사 흐름을 확인한다.
- docs/REVERSE_PROXY_SETUP_GUIDE.md 절차를 따라 외부 HTTPS·SSE·쿠키를 검증한다.

완료 기준: 배포 후보가 모든 자동 검사와 수동 행사 시나리오를 통과한다.

## 14. 검증 계획

### 14.1 자동 테스트

- backend JUnit: 상태 전이, open 조건, 정확 일치 HMAC, 중복 명단, 슬롯 충돌, 첫 제출 승자, 링크 재발급, 세션 만료, 삭제 규칙
- backend 통합 테스트: PostgreSQL·MinIO를 포함한 암호화 저장·복호화 렌더·Flyway·HTTP 권한 경계
- frontend Vitest: 라우트 가드, 자동 저장 상태, 가져오기 오류 표시, 서명 점 제한, 지원 불가 화면
- Playwright Chromium·WebKit: 관리자 준비→보드 열기→태블릿 식별→서명 제출→전체보기 갱신과 마감 PNG의 두 핵심 흐름

### 14.2 수동 QA

1. iPadOS 16 이상 Safari와 Android 12 이상 Chrome에서 세로·가로로 identity 입력과 펜·터치 서명을 완료한다.
2. 같은 공용 링크에서 잘못된 정보, 이미 제출한 사람, 설정 중·마감 보드, 재발급된 링크가 일반 안내만 보이는지 확인한다.
3. 행사장 NAT와 비슷한 네트워크에서 P95 25명 이하의 연속 식별·제출 흐름이 rate limit에 막히지 않는지 확인한다.
4. 관리자 편집 중 슬롯 충돌, 배경 일반 교체, 새 이미지 비율 채택, 제출 서명 이동·크기 변경을 확인한다.
5. 마감 뒤 PNG가 원본 비율·해상도, 흰 슬롯, 검정 서명만 포함하고 이름·UI가 없는지 확인한다.
6. final.png를 동시에 두 번 요청해 첫 요청만 렌더링하고 두 번째 요청이 재시도 안내를 받는지 확인한다.
7. MinIO 객체와 PostgreSQL 원시 저장소에서 명단 원문·서명 획 평문이 보이지 않는지 확인한다.
8. master key 없이 backend가 시작하지 않는지, 복구 키로 기존 데이터를 읽는지 확인한다.
9. HTTPS, Secure 쿠키, 12시간 만료 안내, 로그인 5회 실패 15분 차단, link 재발급의 signer session 무효화를 확인한다.
10. 삭제 뒤 보드 링크, 전체보기, final.png, MinIO 객체가 접근 불가인지 확인한다.

## 15. OMO plan으로 넘길 완료 조건

OMO plan은 다음을 구현 작업으로 분해해야 한다.

- 각 단계의 파일 소유권, 의존성, API 계약, Flyway migration 순서
- encryption·HMAC·키 복구를 포함한 테스트 가능한 데이터 경계
- frontend canvas·slot editor·SSE의 실제 브라우저 QA 절차
- Compose·GHCR·Portainer·Nginx Proxy Manager 배포 절차
- DB·MinIO 외부 백업과 로그인 실패 알림을 현 MVP 작업과 분리한 후속 계획

이 문서에 없는 새 기능은 OMO plan에 넣기 전에 제품·구현 결정으로 다시 확인한다.
