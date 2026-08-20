# MVP physical tablet release checklist

Production readiness stays **blocked** until deployment owner completes every row on enrolled physical hardware. Chromium/WebKit automation is supporting evidence, not a substitute. Use synthetic roster data only and redact participant names, share tokens, cookies, webhook URLs, and credentials from evidence.

## Release identity and network preflight

- [ ] Record release tag, peeled commit, frontend digest, backend digest, and test timestamp; all revision labels equal peeled commit.
- [ ] Record Portainer paired image references and healthy status after webhook; redact registry credentials and webhook URL.
- [ ] `http://signing.lapis0875.com/...` returns `301` with exact `Location: https://signing.lapis0875.com/...`; HTTPS has valid certificate.
- [ ] Backend, PostgreSQL `5432`, MinIO `9000/9001`, Portainer, and webhook are unreachable from public network.
- [ ] `ADMIN_SESSION`/`SIGNER_SESSION` has `Secure; HttpOnly; SameSite=Lax`; `XSRF-TOKEN` has `Secure; SameSite=Lax`; no session cookie travels over HTTP.
- [ ] SSE response arrives without buffering for at least 10 minutes; mutation request buffering remains on; no token appears in URL/access-log evidence.

## Required physical matrix

Complete full scenarios in each cell and record redacted screenshot/network evidence path.

| Device | Portrait | Landscape |
| --- | --- | --- |
| iPadOS 16+ Safari | [ ] | [ ] |
| Android 12+ Chrome | [ ] | [ ] |

For every cell:

- [ ] Open fresh share link/QR, enter required identity plus optional fields, and confirm only own signing context appears.
- [ ] Draw with pen and touch; clear; redraw; submit once; confirm submitted re-entry state prevents duplicate submission.
- [ ] Force one send failure without exposing real data, restore connection, retry, and confirm exactly one accepted signature.
- [ ] Keep manager full view open; confirm SSE update appears promptly without manual reload.
- [ ] Rotate/reissue share link; old link and already-open old tab receive generic invalid response.
- [ ] Close board; confirm signing is blocked. Delete synthetic board; confirm admin/public routes stay unavailable.
- [ ] Rotate device mid-flow; verify controls, canvas mapping, dialogs, and error text remain usable with no clipped action.

## Release decision and rollback receipt

- [ ] Attach redacted evidence paths for all four matrix cells and both happy/failure flows.
- [ ] Record pass/fail and owner. Any unavailable physical platform/control bridge is `BLOCKED: physical tablet device/control bridge unavailable`.
- [ ] If rollback is needed, run immutable rollback selection check, verify both prior image revisions/digests and schema compatibility, then invoke webhook last. Do not run a down migration, volume deletion, backup procedure, or manual Stack recreation.
