# 보드 서명색 단일 릴리스·복구 절차

이 문서는 `V6__board_signature_ink_color.sql`을 포함한 릴리스를 한 번에 배포하고, 실패하면 데이터베이스와 객체 저장소를 같은 시점으로 복구하는 절차다. 운영 트래픽이 없는 승인된 점검 시간에만 수행한다. 배포 전 연습과 검증에는 Task 3가 만든 폐기 가능한 PostgreSQL·객체 저장소만 사용하며 운영 환경에는 접속하지 않는다.

## 기능과 API 계약

서명색은 슬롯 속성이 아니라 보드 최상위 속성 `signatureInkColor`다. 값은 소문자 문자열 `black` 또는 `white`만 허용한다. 새 보드는 `black`으로 생성되고 관리자 보드 목록·상세 응답과 관리자/공개 전체보기 스냅샷에 이 필드가 포함된다.

관리자만 `PATCH /api/v1/admin/boards/{boardId}`로 설정 중인 보드의 색을 바꿀 수 있다. 요청 본문은 다음처럼 최상위 필드를 사용한다. 제목과 함께 보내면 두 변경은 한 트랜잭션으로 성공하거나 함께 취소된다.

```http
PATCH /api/v1/admin/boards/{boardId}
Content-Type: application/json

{"signatureInkColor":"white"}
```

성공하면 HTTP 200 보드 응답의 최상위 `signatureInkColor`가 저장된 값이다. 입력과 상태 오류는 JSON `{"code":"..."}`로 반환된다.

| 조건 | HTTP | `code` | 결과 |
| --- | ---: | --- | --- |
| `signatureInkColor` 값이 `null`·숫자·대문자·그 밖의 문자열 | 400 | `SIGNATURE_INK_COLOR_INVALID` | 변경 없음 |
| 빈 PATCH 본문 | 400 | `BOARD_PATCH_EMPTY` | 변경 없음 |
| 알 수 없는 PATCH 필드 또는 읽을 수 없는 JSON | 400 | `INVALID_REQUEST` | 변경 없음 |
| 배경 이미지가 없는 보드에 `white` 설정 | 409 | `SIGNATURE_INK_BACKGROUND_REQUIRED` | 변경 없음 |
| `OPEN` 또는 `CLOSED` 보드에서 색 설정, 같은 값 재요청 포함 | 409 | `BOARD_NOT_DRAFT` | 변경 없음 |
| 유효한 관리자 세션 없음 | 401 | `UNAUTHORIZED` | 요청 거부 |
| 보드가 없거나 현재 관리자 소유가 아님 | 404 | `BOARD_UNAVAILABLE` | 변경 없음 |

`white`를 선택하려면 먼저 보드 배경 이미지를 올려야 한다. 서버는 DRAFT→OPEN 시작과 CLOSED→OPEN 재열기에서도 배경 존재를 다시 확인하며, 없으면 HTTP 409와 `SIGNATURE_INK_BACKGROUND_REQUIRED`를 반환하고 상태를 바꾸지 않는다. 시작하거나 재열어 `OPEN`이 된 뒤에는 색이 잠기며, 닫힌 뒤에도 바꿀 수 없다.

관리자 편집 화면, 관리자 전체보기, 공개 전체보기와 최종 PNG는 보드 색으로 제출 서명을 그린다. 슬롯 자체는 항상 투명하며 슬롯 API·스냅샷에 슬롯 배경 또는 색 필드가 없다. 공개 서명자가 입력하는 패드의 종이색과 펜은 계속 흰색/검정이고, 저장되는 암호문은 좌표 획 데이터이므로 보드 표시색을 넣거나 다시 암호화하지 않는다.

최종 PNG는 `CLOSED` 보드에서만 내려받는다. 보드 배경이 있으면 배경 위에 보드 색으로 서명을 합성하고 슬롯 채움색·테두리·관리 UI는 넣지 않는다. 배경 없는 `black` 보드는 1920×1080 흰 캔버스에 검정 서명을 합성할 수 있다. `white`인데 배경이 없거나 배경 객체를 읽기·복호화·해석할 수 없으면 HTTP 500과 `FINAL_PNG_UNAVAILABLE`이며 불완전한 PNG를 성공으로 반환하지 않는다. 그 밖의 계약은 인증 없음 401 `UNAUTHORIZED`, 다른 관리자 보드 403 `FINAL_PNG_FORBIDDEN`, 미마감 409 `FINAL_PNG_NOT_CLOSED`, 렌더러 사용 중 503 `FINAL_PNG_BUSY`와 `Retry-After: 1`이다.

## V6 데이터 변경

V6는 `board.signature_ink_color`를 `NOT NULL`, 기본값 `black`, 허용값 `black|white`로 추가하고 `signature_slot.background_color`를 삭제한다. 기존 보드와 기존 `OPEN`·`CLOSED` 보드는 `black`이 되고 모든 슬롯은 투명하게 표시된다. 서명 획·공유 토큰·객체 키·배경 객체의 기존 암호문은 수정하거나 재암호화하지 않는다.

슬롯 배경 열 삭제는 되돌릴 수 없다. 따라서 새 바이너리만 이전 바이너리로 바꾸는 롤백은 금지한다. 복구는 배포 전에 같은 시점에서 만든 데이터베이스 백업과 객체 저장소 백업을 함께 되돌리거나, 새 스키마를 유지한 채 수정 릴리스를 전진 배포하는 두 방법뿐이다. 같은 시점 복구를 하면 백업 이후 정상적으로 들어온 변경도 사라질 수 있으므로, 트래픽 재개 전 복구 시점과 손실 범위를 승인받는다.

## 배포 전 필수 준비와 연습

릴리스 태그의 선후 관계만 확인하는 기존 `scripts/release/validate-rollback.sh`는 스키마 안전 검사가 아니다. 태그 검증이 성공해도 이전 바이너리가 V6 데이터베이스에서 실행 가능한 것은 아니다.

운영 작업 전에 저장소 루트에서 폐기 가능한 자원으로 복구 연습을 두 번 실행한다. 결과에는 V5→V6, 데이터베이스·객체 백업과 복원, 행·암호문 해시·슬롯 열 비교, 스키마 가드의 거부→복구→허용 순서가 있어야 한다.

```sh
E="$PWD/.omo/evidence/board-signature-ink-color/restore-rehearsal"
bash scripts/fixtures/run-ink-color-qa.sh --restore-smoke --evidence-dir "$E/task-9"
bash scripts/fixtures/run-ink-color-qa.sh --restore-smoke --evidence-dir "$E/task-9-repeat"
```

두 실행이 모두 0으로 끝나고 보고서와 정리 결과가 `passed`일 때만 진행한다. 실패·시간 초과·중단 뒤에는 소유 컨테이너, 네트워크, 볼륨, 임시 객체와 자격 증명이 제거됐는지 확인하고 원인을 해결한 뒤 처음부터 다시 연습한다.

실제 프로세스 시작 전 가드는 `psql`, 승인된 0600 권한의 `PGSERVICEFILE`, 그리고 그 파일에서 연결 대상을 명시적으로 선택하는 비어 있지 않은 `PGSERVICE`를 사용한다. 두 환경 변수 중 하나라도 없거나 비어 있으면 실행하지 않는다. 가드는 선택한 서비스를 해석할 수 없는 경우, 연결 실패, 알 수 없는 대상, V5/V6가 섞인 카탈로그를 성공으로 취급하지 않는다. 다음 두 줄은 각각 새 릴리스와 이전 릴리스의 시작 래퍼 안에서, 해당 프로세스 시작 명령보다 먼저 실행해야 하는 필수 게이트다.

새 바이너리 시작 전:

```sh
bash scripts/release/check-ink-color-schema.sh board-ink || exit 1
```

이전 바이너리 복구 시작 전:

```sh
bash scripts/release/check-ink-color-schema.sh legacy || exit 1
```

이 문서는 모든 배포 경로가 가드를 자동으로 실행한다고 주장하지 않는다. 운영자가 실제 런처에 위 순서를 넣고, 실패 시 프로세스 시작 명령이 호출되지 않는지 사전에 확인해야 한다.

## 단일 원자적 릴리스

아래 순서를 나누거나 새·이전 버전을 동시에 운영하지 않는다. 특히 이전 backend 또는 frontend와 V6 데이터베이스를 섞지 않고, 새 backend와 이전 frontend도 섞지 않는다.

1. 새 frontend/backend 이미지를 같은 릴리스 식별자로 빌드하고 배포 대상 다이제스트를 기록한다. 아직 프로세스를 시작하지 않는다.
2. 리버스 프록시의 유지보수 응답을 켜서 새 요청을 차단하고 기존 요청이 끝날 때까지 기다린다. 이어 frontend와 backend를 모두 중지하여 쓰기를 멈춘다.
3. 중지된 같은 시점의 PostgreSQL 논리 백업, MinIO 객체 전체, `master.key`와 필요한 메타데이터를 승인된 백업 위치에 저장한다. 데이터베이스와 객체 백업의 같은 복구 시점 식별자를 기록한다.
4. 그 백업 복제본으로 격리된 복구 연습을 하고 행 수, 암호문 해시, 객체 수·해시가 원본 백업 명세와 같은지 확인한다. 운영 데이터나 운영 자격 증명을 Task 3 fixture에 넣지 않는다.
5. V6 적용 전에 슬롯 배경 분포를 기록한다. 예시는 `SELECT COALESCE(background_color, '<NULL>') AS background_color, COUNT(*) FROM signature_slot GROUP BY 1 ORDER BY 1;`이며 전체 합계도 함께 기록한다.
6. 새 릴리스의 전용 마이그레이션 작업으로 V6를 한 번 적용한다. 성공 뒤 카탈로그가 V6인지 검사한다. 실패하면 어떤 서비스도 시작하지 말고 같은 시점 백업을 복구한다.
7. 새 프로세스 래퍼에서 `board-ink` 가드를 통과시킨 뒤 같은 릴리스의 backend와 frontend를 함께 시작한다. 둘 다 healthy가 되기 전에는 트래픽을 열지 않는다.
8. 캐시와 서비스 워커를 재사용하지 않는 새 비공개 브라우저를 열어 관리자 로그인, 보드 생성, 배경 업로드, 흰색 설정, 시작, 공개 전체보기, 닫기, 최종 PNG를 실제 경로로 확인한다. 배포 전에 열어 둔 브라우저나 오래된 탭은 사용하지 않는다.
9. DB 스키마, backend/frontend 릴리스 식별자, health, 브라우저 스모크와 감사 로그가 모두 일치할 때만 유지보수 응답을 끄고 트래픽을 재개한다.

트래픽 중지, 백업, 분포 집계, V6 적용, 동시 재시작, 새 브라우저 스모크, 트래픽 재개의 순서를 모두 한 변경 창에서 수행한다. 한 단계의 증거가 없으면 다음 단계로 넘어가지 않는다.

## 실패와 복구

V6 적용 전 실패는 서비스를 계속 중지한 채 원인을 해결하거나 기존 V5 상태를 확인한 다음 `legacy` 가드를 통과시켜 이전 frontend/backend를 함께 시작한다.

V6 적용 뒤에는 바이너리만 되돌리지 않는다. 우선 서비스를 모두 중지하고 다음 중 하나를 선택한다.

- 전진 수정: V6 스키마를 유지하고 수정된 동일 세대 frontend/backend를 배포한다. 시작 전에 `board-ink` 가드를 통과해야 한다.
- 같은 시점 복구: PostgreSQL과 MinIO 객체를 배포 전의 동일 복구 시점으로 함께 복원하고 행 수, 암호문 해시, 객체 수·해시, `signature_slot.background_color` 존재와 `board.signature_ink_color` 부재를 확인한다. 그 뒤에만 `legacy` 가드를 통과시켜 이전 frontend/backend를 함께 시작한다.

복구 뒤 가드가 `INCOMPATIBLE` 또는 `INDETERMINATE`이면 이전 바이너리를 시작하지 않는다. DB만 복구하거나 객체만 복구하지 않으며, 오래된 브라우저 탭으로 성공을 판정하지 않는다. 복구 후 새 비공개 브라우저로 검정 서명 흐름과 배경/최종 PNG를 다시 확인하고 승인된 손실 범위를 기록한 뒤 트래픽을 재개한다.
