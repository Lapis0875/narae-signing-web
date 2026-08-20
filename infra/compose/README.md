# Private Compose stack

The application LXC must have at least **8 vCPU, 16 GiB RAM, and 120 GiB available persistent disk at `/srv/narae-signing`**. A smaller host must not run this stack, the private runner, or release preflight. Build frontend and backend images serially.

Create `/srv/narae-signing/postgres`, `/srv/narae-signing/minio`, and `/srv/narae-signing/secrets`. Put exactly 32 random bytes in `/srv/narae-signing/secrets/master.key`, owned and readable only by root. Copy `.env.example` to `.env` and populate unique secrets outside Git.

Only `frontend` publishes host port 8080. The stable private addresses are frontend `172.30.0.10`, backend `172.30.0.20`, PostgreSQL `172.30.0.30`, and MinIO `172.30.0.40`; backend trusts `X-Narae-Client-IP` only from `172.30.0.10` and ignores `X-Forwarded-For`.

Run `docker compose -f infra/compose/compose.yml up --build -d`. `frontend`, `backend`, `postgres`, and `minio` must become healthy before serving traffic.
