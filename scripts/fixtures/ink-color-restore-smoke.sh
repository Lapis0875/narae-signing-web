#!/usr/bin/env bash
set -euo pipefail

root=$1
compose_file=$2
project=$3
temporary_root=$4
evidence_dir=$5

run_bounded() {
    local seconds=$1
    shift
    python3 "$root/scripts/fixtures/task30-live-run.py" --timeout "$seconds" "$@"
}

compose_exec() {
    run_bounded 60 docker compose -p "$project" -f "$compose_file" exec -T "$@"
}

psql_value() {
    compose_exec postgres psql -X -A -t -q -v ON_ERROR_STOP=1 \
        -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"
}

guard_status() {
    local service=$1
    local target=$2
    local output=$3
    local result=0
    compose_exec -e "PGSERVICEFILE=/qa/$service.pg_service.conf" -e "PGSERVICE=$service" \
        postgres sh /repo/scripts/release/check-ink-color-schema.sh "$target" \
        > "$output" 2>&1 || result=$?
    printf '%s\n' "$result"
}

object_hash() {
    compose_exec minio-client sh -ec \
        'mc alias set qa http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc cat "qa/$APP_MINIO_BUCKET/background/synthetic.bin"' |
        shasum -a 256 | awk '{print $1}'
}

for migration in V1__core_schema.sql V2__spring_session.sql V3__login_defense.sql \
    V4__deletion_job_lease.sql V5__signature_draft_lease.sql; do
    compose_exec postgres psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        -f "/repo/backend/src/main/resources/db/migration/$migration" >/dev/null
done

compose_exec postgres psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null <<'SQL'
INSERT INTO admin_user (id, email, password_hash, status)
VALUES ('10000000-0000-0000-0000-000000000001', 'restore-fixture@example.invalid', 'synthetic-hash', 'ACTIVE');
INSERT INTO board (
    id, owner_id, title, status, canvas_width, canvas_height, share_token_lookup_hash,
    share_token_ciphertext, share_token_nonce, share_token_key_version
) VALUES (
    '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
    'restore fixture', 'DRAFT', 1280, 720, decode('01020304', 'hex'),
    decode('102030405060', 'hex'), decode('a0b0c0d0', 'hex'), 1
);
INSERT INTO roster_entry (id, board_id, encrypted_identity, identity_nonce, identity_key_version, identity_hmac)
VALUES
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', decode('01','hex'), decode('11','hex'), 1, decode('21','hex')),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', decode('02','hex'), decode('12','hex'), 1, decode('22','hex')),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', decode('03','hex'), decode('13','hex'), 1, decode('23','hex'));
INSERT INTO signature_slot (id, roster_entry_id, placement_status, background_color)
VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'UNPLACED', 'black'),
    ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'UNPLACED', 'white'),
    ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', 'UNPLACED', NULL);
SQL

printf 'synthetic encrypted object payload v5\n' > "$temporary_root/object.bin"
chmod 600 "$temporary_root/object.bin"
compose_exec minio-client sh -ec \
    'mc alias set qa http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mb --ignore-existing "qa/$APP_MINIO_BUCKET" >/dev/null; mc cp /qa/object.bin "qa/$APP_MINIO_BUCKET/background/synthetic.bin" >/dev/null'

pre_rows=$(psql_value "SELECT concat_ws(',', (SELECT count(*) FROM board), (SELECT count(*) FROM roster_entry), (SELECT count(*) FROM signature_slot));")
pre_distribution=$(psql_value "SELECT string_agg(color || ':' || count, ',' ORDER BY color) FROM (SELECT COALESCE(background_color, 'null') AS color, count(*)::text AS count FROM signature_slot GROUP BY 1) values_by_color;")
ciphertext_hex=$(psql_value "SELECT encode(share_token_ciphertext, 'hex') FROM board;")
pre_ciphertext_hash=$(printf '%s' "$ciphertext_hex" | shasum -a 256 | awk '{print $1}')
pre_object_hash=$(object_hash)

compose_exec postgres pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -f /qa/backup/db/v5.dump
compose_exec minio-client sh -ec \
    'mc alias set qa http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc mirror "qa/$APP_MINIO_BUCKET" /qa/backup/objects >/dev/null'
backup_db_hash=$(shasum -a 256 "$temporary_root/backup/db/v5.dump" | awk '{print $1}')
backup_object_hash=$(shasum -a 256 "$temporary_root/backup/objects/background/synthetic.bin" | awk '{print $1}')
[[ "$backup_object_hash" == "$pre_object_hash" ]] || exit 41

compose_exec postgres psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -f /repo/backend/src/main/resources/db/migration/V6__board_signature_ink_color.sql >/dev/null
v6_legacy_status=$(guard_status restore legacy "$evidence_dir/v6-legacy-guard.txt")
v6_board_ink_status=$(guard_status restore board-ink "$evidence_dir/v6-board-ink-guard.txt")
[[ "$v6_legacy_status" -ne 0 && "$v6_board_ink_status" -eq 0 ]] || exit 42

old_binary_status=0
compose_exec -e PGSERVICEFILE=/qa/restore.pg_service.conf -e PGSERVICE=restore postgres sh -ec \
    'sh /repo/scripts/release/check-ink-color-schema.sh legacy >/qa/old-binary-guard.txt 2>&1 || exit 1; printf started > /qa/old-binary-started' \
    || old_binary_status=$?
cp "$temporary_root/old-binary-guard.txt" "$evidence_dir/old-binary-legacy-guard.txt"
[[ "$old_binary_status" -ne 0 && ! -e "$temporary_root/old-binary-started" ]] || exit 43
old_binary_v6_status=$(guard_status restore board-ink "$evidence_dir/old-binary-v6-catalog.txt")
[[ "$old_binary_v6_status" -eq 0 ]] || exit 44

compose_exec postgres psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -c "UPDATE board SET title = 'post-backup mutation';" >/dev/null
printf 'post-backup object mutation\n' > "$temporary_root/object.bin"
compose_exec minio-client sh -ec \
    'mc alias set qa http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc cp /qa/object.bin "qa/$APP_MINIO_BUCKET/background/synthetic.bin" >/dev/null'

compose_exec postgres dropdb --force -U "$POSTGRES_USER" "$POSTGRES_DB"
compose_exec postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"
compose_exec postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" /qa/backup/db/v5.dump
compose_exec minio-client sh -ec \
    'mc alias set qa http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null; mc rm --recursive --force "qa/$APP_MINIO_BUCKET" >/dev/null; mc mirror /qa/backup/objects "qa/$APP_MINIO_BUCKET" >/dev/null'

restored_legacy_status=$(guard_status restore legacy "$evidence_dir/restored-legacy-guard.txt")
restored_board_ink_status=$(guard_status restore board-ink "$evidence_dir/restored-board-ink-guard.txt")
[[ "$restored_legacy_status" -eq 0 && "$restored_board_ink_status" -ne 0 ]] || exit 45
restored_rows=$(psql_value "SELECT concat_ws(',', (SELECT count(*) FROM board), (SELECT count(*) FROM roster_entry), (SELECT count(*) FROM signature_slot));")
restored_distribution=$(psql_value "SELECT string_agg(color || ':' || count, ',' ORDER BY color) FROM (SELECT COALESCE(background_color, 'null') AS color, count(*)::text AS count FROM signature_slot GROUP BY 1) values_by_color;")
restored_ciphertext_hex=$(psql_value "SELECT encode(share_token_ciphertext, 'hex') FROM board;")
restored_ciphertext_hash=$(printf '%s' "$restored_ciphertext_hex" | shasum -a 256 | awk '{print $1}')
restored_object_hash=$(object_hash)
[[ "$restored_rows" == "$pre_rows" ]] || exit 46
[[ "$restored_distribution" == "$pre_distribution" ]] || exit 47
[[ "$restored_ciphertext_hash" == "$pre_ciphertext_hash" ]] || exit 48
[[ "$restored_object_hash" == "$pre_object_hash" ]] || exit 49

python3 - "$evidence_dir/restore-result.json" \
    "$pre_rows" "$pre_distribution" "$pre_ciphertext_hash" "$pre_object_hash" \
    "$backup_db_hash" "$backup_object_hash" "$v6_legacy_status" "$v6_board_ink_status" \
    "$old_binary_status" "$old_binary_v6_status" "$restored_legacy_status" \
    "$restored_board_ink_status" "$restored_rows" "$restored_distribution" \
    "$restored_ciphertext_hash" "$restored_object_hash" <<'PY'
import json
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
output.write_text(json.dumps({
    "backup": {
        "databaseSha256": sys.argv[6],
        "objectSha256": sys.argv[7],
        "quiesced": True,
    },
    "beforeMigration": {
        "ciphertextSha256": sys.argv[4],
        "objectSha256": sys.argv[5],
        "rowCounts": sys.argv[2],
        "slotBackgroundDistribution": sys.argv[3],
    },
    "oldBinaryProbe": {
        "catalogRemainedBoardInkStatus": int(sys.argv[11]),
        "guardExit": int(sys.argv[10]),
        "processStarted": False,
    },
    "restored": {
        "boardInkGuardExit": int(sys.argv[13]),
        "ciphertextSha256": sys.argv[16],
        "legacyGuardExit": int(sys.argv[12]),
        "objectSha256": sys.argv[17],
        "rowCounts": sys.argv[14],
        "slotBackgroundDistribution": sys.argv[15],
    },
    "v6": {
        "boardInkGuardExit": int(sys.argv[9]),
        "legacyGuardExit": int(sys.argv[8]),
    },
}, indent=2, sort_keys=True) + "\n")
PY

cat > "$evidence_dir/restore-transcript.txt" <<EOF
fixture=synthetic-v5
backup_consistency=quiesced-db-and-object-store
pre_rows=$pre_rows
pre_slot_background_distribution=$pre_distribution
v6_legacy_guard_status=$v6_legacy_status
v6_board_ink_guard_status=$v6_board_ink_status
old_binary_guard_status=$old_binary_status
old_binary_process_started=false
old_binary_catalog_remained_v6_status=$old_binary_v6_status
restored_legacy_guard_status=$restored_legacy_status
restored_board_ink_guard_status=$restored_board_ink_status
restored_rows_match=true
restored_ciphertext_hash_match=true
restored_object_hash_match=true
scope=disposable-release-restore-rehearsal-not-production-rollback
EOF
