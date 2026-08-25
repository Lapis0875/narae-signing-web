# Proxmox VE LXC에서 나래 서명 배포하기

이 문서는 Proxmox VE 홈서버에 **Ubuntu Server 22.04 LTS (Jammy)** LXC를 만들고, 그 안에 Docker Engine을 설치한 뒤 나래 서명 Docker Compose 스택을 배포하는 절차다. 애플리케이션의 환경 변수와 HTTPS 설정은 [빠른 배포 가이드](DEPLOY_GUIDE.md)를 기준으로 한다.

LXC는 Proxmox 호스트의 Linux 커널을 함께 사용한다. Docker 실행에는 `nesting=1`과 `keyctl=1`이 필요하므로, 이 방식은 신뢰하는 단일 애플리케이션 전용 LXC에만 권장한다. 임의 사용자에게 LXC 접근 권한을 주거나 더 강한 격리가 필요하면 LXC 대신 Ubuntu 22.04 VM을 사용한다.

```text
Internet
  └─ 리버스 프록시 LXC (80/443, TLS)
       └─ 나래 서명 LXC (8080만 프록시에서 접근)
            └─ Docker Compose
                 ├─ frontend
                 ├─ backend
                 ├─ postgres
                 └─ minio
```

## 1. 권장 자원과 가상화 설정

현재 Compose 스택은 런타임에 서비스별 CPU 제한 합계가 약 3.75코어, 메모리 제한 합계가 약 7.25 GiB다. 이미지 빌드, 데이터베이스 캐시, Docker 자체 오버헤드를 고려해 아래 값을 권장한다.

| 항목 | Proxmox 호스트 권장 | 나래 서명 LXC 권장 | 이유 |
| --- | --- | --- | --- |
| CPU | 최소 8 논리 코어, 권장 12 이상 | 6코어, `cpulimit=0` | 런타임 외에 frontend/backend 순차 빌드 여유가 필요하다. |
| 메모리 | 최소 24 GiB, 권장 32 GiB | 16 GiB, swap `0` MiB | LXC의 swap 의존을 막고, Proxmox 호스트와 다른 게스트에 RAM을 남긴다. |
| 디스크 | SSD/NVMe 스토리지에 여유 공간 확보 | rootfs 160 GiB 이상 | `/srv/narae-signing`에 최소 120 GiB를 지속적으로 남기고 Docker 이미지·빌드 계층 여유를 둔다. |
| 네트워크 | 고정 내부 IP 또는 DHCP 예약 | `vmbr0`의 단일 veth | 리버스 프록시 방화벽 규칙과 대상 주소가 변하지 않게 한다. |

- Proxmox 호스트 자체가 물리 RAM 16 GiB뿐이면 이 LXC에 16 GiB를 배정하지 않는다. 하드웨어를 늘리거나 다른 게스트를 옮긴 뒤 진행한다.
- CPU 우선순위는 기본값을 유지한다. 다른 게스트와 경합할 때만 `cpuunits`를 조정하고, 일반적으로 하드 CPU 제한은 걸지 않는다.
- LXC에는 KVM VM의 `CPU type`, `SSD emulation`, `I/O thread` 설정이 없다. SSD 성능은 **Proxmox에서 선택한 실제 SSD/NVMe 스토리지**와 LXC rootfs 용량으로 결정된다.
- thin-provisioned 스토리지를 쓴다면 대량 이미지·데이터 삭제 뒤 Proxmox 호스트에서 `pct fstrim <CTID>`를 실행할 수 있다. 지원 여부는 스토리지 구성에 따라 확인한다.
- rootfs 안의 `/srv/narae-signing`을 사용한다. 무분별한 host bind mount는 unprivileged LXC의 UID 매핑, snapshot, backup을 복잡하게 만든다.

## 2. LXC 보안 선택

새 CT는 반드시 **Unprivileged container**로 만든다. privileged LXC나 `lxc.apparmor.profile = unconfined`를 Docker 문제의 일반적인 해결책으로 사용하지 않는다.

Docker를 위해 필요한 CT feature는 다음 두 개뿐이다.

```text
nesting=1
keyctl=1
```

`keyctl=1`은 unprivileged LXC에서 Docker를 쓰는 데 필요하며, `nesting=1`은 호스트의 일부 procfs·sysfs 정보를 게스트에 노출한다. 따라서 `fuse`, `mknod`, 임의 디바이스 패스스루는 추가로 켜지 않는다. Proxmox의 LXC feature 설명과 보안 제한은 [공식 `pct` 매뉴얼](https://pve.proxmox.com/pve-docs/pct.1.html)을 참고한다.

## 3. Proxmox 웹 UI에서 LXC 만들기

1. 대상 Proxmox 노드의 **local → CT Templates**에서 `ubuntu-22.04-standard` 계열의 Ubuntu 22.04 LTS 템플릿을 내려받는다.
2. **Create CT**를 열고 CT ID, hostname, root 암호 또는 SSH 공개키를 설정한다. 외부 SSH는 비밀번호보다 공개키를 사용한다.
3. **Unprivileged container**를 선택한다.
4. Disk에서 SSD/NVMe 기반 Proxmox 스토리지를 선택하고 크기를 `160 GiB` 이상으로 지정한다.
5. CPU는 `6` cores, Memory는 `16384` MiB, Swap은 `0` MiB로 설정한다.
6. Network에서 `vmbr0`을 사용하고, DHCP 예약 또는 고정 IPv4를 정한다. CT Firewall을 켠다.
7. 생성 뒤 아직 Docker를 설치하기 전에 CT의 **Options → Features**에서 `Nesting`과 `Keyctl`을 켠다. UI 권한으로 설정할 수 없으면 Proxmox 호스트의 root 셸에서 다음 명령을 사용한다.

```sh
pct set <CTID> --cores 6 --memory 16384 --swap 0 --features nesting=1,keyctl=1 --onboot 1
pct start <CTID>
pct config <CTID>
pct enter <CTID>
```

`pct config <CTID>` 결과에서 `unprivileged: 1`과 `features: keyctl=1,nesting=1`을 확인한다. `onboot=1`은 Proxmox 재부팅 뒤 CT가 자동으로 시작되게 한다.

## 4. LXC 안에 Docker Engine 설치

이 절의 명령은 LXC 콘솔 또는 SSH의 **root 셸**에서 실행한다. 일반 사용자로 접속했다면 각 관리 명령 앞에 `sudo`를 붙인다. Docker group은 root 수준 권한을 주므로, 편의를 위해 일반 사용자를 추가하지 않는다.

새 Ubuntu 22.04 LXC에서 패키지를 갱신하고 Docker 공식 APT 저장소를 등록한다.

```sh
apt update
apt upgrade -y
apt install -y ca-certificates curl git

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

cat <<EOF > /etc/apt/sources.list.d/docker.sources
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker run --rm hello-world
docker compose version
```

`hello-world`가 성공하면 Docker Engine과 nested LXC feature가 함께 동작하는 것이다. `operation not permitted` 또는 cgroup/keyring 오류가 나면 privileged LXC로 바꾸지 말고 먼저 Proxmox 호스트에서 `pct config <CTID>`의 `nesting`·`keyctl` 값을 다시 확인한다.

Docker가 publish한 포트는 LXC 안의 UFW 규칙만으로는 기대대로 차단되지 않을 수 있다. 공개 범위는 Proxmox CT Firewall과 리버스 프록시 네트워크 규칙에서 우선 제한한다. Docker의 방화벽 동작은 [Docker 공식 문서](https://docs.docker.com/engine/network/packet-filtering-firewalls/)를 참고한다.

## 5. 소스 배치와 애플리케이션 배포

저장소를 LXC 내부의 일반 경로에 배치한다. private 저장소는 SSH deploy key 또는 승인된 인증 방식을 사용하며, access token을 clone URL·셸 이력·문서에 넣지 않는다.

```sh
git clone <REPOSITORY_URL> /opt/narae-signing
cd /opt/narae-signing
```

이후 [빠른 배포 가이드](DEPLOY_GUIDE.md)의 **2. 빠른 시작 순서**와 **3. `.env` 구성**을 그대로 수행한다. LXC root 셸에서는 그 문서의 `docker compose` 명령을 그대로 쓸 수 있다.

최소 기동 순서는 다음과 같다. `.env`의 모든 값, 특히 `APP_PUBLIC_ORIGIN`과 `NARAE_DATA_ROOT=/srv/narae-signing`은 먼저 채워야 한다.

```sh
install -d -m 700 /srv/narae-signing/{postgres,minio,secrets}
umask 077
dd if=/dev/urandom of=/srv/narae-signing/secrets/master.key bs=32 count=1 status=none
chown 10001:10001 /srv/narae-signing/secrets/master.key
chmod 400 /srv/narae-signing/secrets/master.key

cp infra/compose/.env.example infra/compose/.env
chmod 600 infra/compose/.env
$EDITOR infra/compose/.env

docker compose --env-file infra/compose/.env -f infra/compose/compose.yml config --quiet
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build frontend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml build backend
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml up -d --wait
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml ps
```

`master.key`는 정확히 32 bytes여야 하며, 현재 backend 컨테이너의 UID `10001`이 읽을 수 있어야 한다. 키를 Git 또는 backup 로그에 포함하지 않고, 데이터가 생긴 뒤에는 임의로 바꾸지 않는다.

## 6. 네트워크와 HTTPS 공개

나래 서명 LXC의 공개 포트는 frontend의 `8080`뿐이다. Docker Compose 내부의 backend, PostgreSQL, MinIO는 Proxmox 네트워크나 WAN에 공개하지 않는다.

Proxmox CT Firewall에서 허용할 일반적인 인바운드는 다음처럼 최소화한다.

| 원본 | 대상 포트 | 용도 |
| --- | --- | --- |
| 관리용 LAN | `22` | 필요한 경우의 SSH 관리 |
| 리버스 프록시 LXC의 고정 IP | `8080` | frontend로의 내부 프록시 연결 |
| 그 외 | 허용하지 않음 | backend·DB·MinIO와 frontend 직접 공개 방지 |

리버스 프록시의 80/443, TLS 인증서, forwarded header, SSE 설정은 [리버스 프록시 설정 가이드](REVERSE_PROXY_SETUP_GUIDE.md)를 따른다. `APP_PUBLIC_ORIGIN`은 프록시 내부 URL이 아니라 사용자가 브라우저에서 여는 정확한 `https://` 주소여야 한다.

## 7. 배포 검증과 운영

Proxmox 호스트와 LXC에서 다음 순서로 확인한다.

```sh
# Proxmox 호스트
pct status <CTID>
pct exec <CTID> -- free -h
pct exec <CTID> -- df -h /

# LXC 내부 /opt/narae-signing
docker compose --env-file infra/compose/.env -f infra/compose/compose.yml ps
curl -fsS http://127.0.0.1:8080/health
```

health 응답은 HTTP 200과 `{"status":"UP"}`이어야 한다. 이후 외부 네트워크에서 `https://<PUBLIC_DOMAIN>/health`를 확인하고, [수동 테스트 가이드](MANUAL_TEST_GUIDE.md)로 관리자 로그인과 보드 흐름을 검증한다.

처음 배포가 정상임을 확인한 뒤 Proxmox 삭제 방지 플래그를 켤 수 있다.

```sh
pct set <CTID> --protection 1
```

이 플래그는 실수로 CT disk를 삭제하거나 변경하는 작업을 막는다. 의도적으로 크기를 변경하거나 CT를 제거할 때만 일시적으로 해제한다.

용량을 정리한 뒤 thin-provisioned 스토리지에 여유를 돌려줘야 하면 Proxmox 호스트에서 다음을 실행한다.

```sh
pct fstrim <CTID>
```

이는 CT의 rootfs와 mount point에 TRIM을 시도한다. Proxmox 스토리지에서 이를 지원하지 않으면 실패하거나 효과가 없을 수 있으므로, 정기 실행 전에는 한 번 검증한다.

## 8. 문제 발생 시 우선 확인할 항목

| 증상 | 확인 순서 |
| --- | --- |
| `docker run`이 권한 오류로 실패 | `pct config <CTID>`에서 `unprivileged: 1`, `nesting=1`, `keyctl=1`을 확인하고 CT를 재시작한다. |
| backend가 key 파일을 읽지 못함 | LXC 내부에서 `/srv/narae-signing/secrets/master.key`의 길이 32, owner UID `10001`, mode `400`을 확인한다. |
| 프록시가 502를 반환 | 프록시 LXC에서 나래 서명 LXC의 `http://<LXC_IP>:8080/health` 연결, Proxmox CT Firewall, `docker compose ps`를 확인한다. |
| 디스크가 빨리 찬다 | `df -h /`, Docker 이미지·로그 사용량, `/srv/narae-signing` 데이터 증가를 확인한다. 삭제 전에는 대상이 이 전용 LXC인지 확인한다. |
| 로그인 세션이 생기지 않음 | HTTPS URL, `.env`의 `APP_PUBLIC_ORIGIN`, 프록시의 `X-Forwarded-Proto: https` 전달을 확인한다. |

`unprivileged: 0`, `lxc.apparmor.profile = unconfined`, 임의 device passthrough는 문제 해결을 위해 추가하지 않는다. 그것이 필요해 보이면 먼저 LXC가 아닌 VM 전환이 더 적절한지 검토한다.

## 9. 참고 문서

- [나래 서명 빠른 배포 가이드](DEPLOY_GUIDE.md)
- [나래 서명 리버스 프록시 설정 가이드](REVERSE_PROXY_SETUP_GUIDE.md)
- [나래 서명 수동 테스트 가이드](MANUAL_TEST_GUIDE.md)
- [Proxmox VE `pct` 매뉴얼](https://pve.proxmox.com/pve-docs/pct.1.html)
- [Docker Engine: Ubuntu 설치](https://docs.docker.com/engine/install/ubuntu/)
- [Docker Engine: 방화벽 동작](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
