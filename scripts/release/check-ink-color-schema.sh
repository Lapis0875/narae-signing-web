#!/bin/sh
set -eu

target=${1:-}

verdict() {
    printf '%s: %s\n' "$1" "$2"
}

case "$target" in
    legacy|board-ink) ;;
    *) verdict INVALID target >&2; exit 64 ;;
esac

if [ "$#" -ne 1 ]; then
    verdict INVALID target >&2
    exit 64
fi

if ! command -v psql >/dev/null 2>&1; then
    verdict INDETERMINATE connection >&2
    exit 69
fi

service_file=${PGSERVICEFILE:-}
if [ -z "$service_file" ] || [ ! -f "$service_file" ] || [ -L "$service_file" ]; then
    verdict INDETERMINATE connection >&2
    exit 69
fi

if permissions=$(stat -f '%Lp' "$service_file" 2>/dev/null); then
    :
elif permissions=$(stat -c '%a' "$service_file" 2>/dev/null); then
    :
else
    verdict INDETERMINATE connection >&2
    exit 69
fi
if [ "$permissions" != 600 ]; then
    verdict INDETERMINATE connection >&2
    exit 69
fi

catalog_query="
WITH facts AS (
  SELECT
    to_regclass('public.board') IS NOT NULL AS board_exists,
    to_regclass('public.signature_slot') IS NOT NULL AS slot_exists,
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = to_regclass('public.signature_slot')
        AND attname = 'background_color' AND attnum > 0 AND NOT attisdropped
    ) AS old_exists,
    EXISTS (
      SELECT 1 FROM pg_attribute
      WHERE attrelid = to_regclass('public.board')
        AND attname = 'signature_ink_color' AND attnum > 0 AND NOT attisdropped
    ) AS new_exists,
    COALESCE((
      SELECT attnotnull FROM pg_attribute
      WHERE attrelid = to_regclass('public.board')
        AND attname = 'signature_ink_color' AND attnum > 0 AND NOT attisdropped
    ), false) AS new_not_null,
    COALESCE((
      SELECT pg_get_expr(adbin, adrelid) = '''black''::character varying'
      FROM pg_attrdef
      WHERE adrelid = to_regclass('public.board')
        AND adnum = (
          SELECT attnum FROM pg_attribute
          WHERE attrelid = to_regclass('public.board')
            AND attname = 'signature_ink_color' AND attnum > 0 AND NOT attisdropped
        )
    ), false) AS new_black_default,
    EXISTS (
      SELECT 1
      FROM pg_constraint constraint_row
      WHERE conrelid = to_regclass('public.board')
        AND contype = 'c'
        AND convalidated
        AND lower(pg_get_constraintdef(oid)) LIKE '%signature_ink_color%'
        AND ARRAY(
          SELECT DISTINCT (captured.values)[1]
          FROM regexp_matches(pg_get_constraintdef(constraint_row.oid), '''([^'']*)''', 'g')
            AS captured(values)
          ORDER BY (captured.values)[1]
        ) = ARRAY['black', 'white']
    ) AS new_color_check
)
SELECT concat_ws('|', board_exists, slot_exists, old_exists, new_exists,
                 new_not_null, new_black_default, new_color_check)
FROM facts;
"

if ! facts=$(psql -X -A -t -q -v ON_ERROR_STOP=1 -c \
    "SET default_transaction_read_only = on; $catalog_query" 2>/dev/null); then
    verdict INDETERMINATE connection >&2
    exit 69
fi

case "$target:$facts" in
    legacy:t\|t\|t\|f\|f\|f\|f)
        verdict COMPATIBLE legacy
        ;;
    board-ink:t\|t\|f\|t\|t\|t\|t)
        verdict COMPATIBLE board-ink
        ;;
    *)
        verdict INCOMPATIBLE "$target" >&2
        exit 1
        ;;
esac
