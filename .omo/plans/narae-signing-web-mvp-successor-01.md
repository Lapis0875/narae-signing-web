# Narae Signing Web MVP Successor 01

## TL;DR
> Summary:      Continue from verified `main` after historical Todos 1-12, delivering board authoring, exact roster/slot rules, encrypted backgrounds and signatures, signer flow, realtime view, PNG, deletion, and local release readiness.
> Deliverables:
> - Owner-scoped Korean signing-board MVP from board creation through permanent deletion
> - Conflict-safe feature branches, one atomic implementation-unit commit per task, and retained evidence
> - Local release workflow and full integrated verification without push/deploy unless separately authorized
> - Physical iPad/Android gate that blocks production-readiness when device or deployment authority is absent
> Effort:       XL
> Risk:         High - concurrency, encrypted private data, image memory, tablet input, and immutable recovery provenance cross several boundaries.

## Scope
### Must have
- Preserve historical Todos 1-12, secure admin/signer sessions, CSRF, encryption, admin commands, auth UI, current private Compose topology, and recovery records unchanged.
- Implement owner-scoped boards with internal `DRAFT`, `OPEN`, `CLOSED`, `DELETING` states mapped to Korean product states; title, private share-link retrieval/reissue, and `Cache-Control: no-store, private` owner responses.
- Implement exact raw roster identity, maximum 50 people, direct edit, paste, UTF-8 CSV, one-sheet XLSX, deterministic safe errors, one slot per person, normalized non-overlapping geometry, revision invalidation, and server-authoritative lifecycle checks.
- Implement PNG/JPEG background normalization and encrypted private MinIO storage, tablet pointer signing, first-wins encrypted submission, manager-only SSE/full view, current closed-board PNG, and access-first permanent deletion with durable object cleanup.
- Adopt `DESIGN.md` byte-identically, then implement a dedicated visual foundation using only its operational color/type/spacing/radius/elevation tokens; build Korean desktop-admin and tablet-signer surfaces with accessibility basics and real browser QA.
- Implement manual-tag GHCR/Portainer workflow and release preflight locally; actual push, tag publication, webhook, production mutation, and deployment require separate explicit authority.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No legal-signature claim, public signup, email verification/reset, multi-admin ownership, public full view, smartphone signing, multilingual UI, anonymous slots, templates, text/shape/PDF/multipage editor, individual signature export, external backup, or login-failure alert.
- No Redis, queue, worker service, microservice split, WebSocket, public MinIO URL, client-stored auth/share token, shared frontend/backend types package, or live in-progress stroke broadcast.
- No edits to historical `.omo/plans/narae-signing-web-mvp.md`, Recovery-01/02/03 plans, their manifests/handoffs/evidence, existing refs, or retained historical worktrees. Successor activation may create a distinct runtime work record only through the coordinator transition below; it must preserve Recovery-03's blocked/failed status and Policy-A provenance.
- No source work directly on `main`; no force-push, rebase of shared work, hard reset, CAS rewrite, push, release tag, registry write, Portainer call, or production change.
- No `docker compose up/down`, Docker network/volume mutation, Testcontainers, or competing stack while the retained manual stack at `127.0.0.1:18083` is active, except Task 30's explicitly authorized suspend/run/restore window.
- No new abstraction, dependency, or schema migration unless a concrete task needs it. Reuse existing JDBC, crypto, error, session, query, confirmation, toast, Tailwind v4, and proxy boundaries.

## Verification strategy
> All executable verification is agent-run. Explicit external authorities are prerequisites, never assertions: Task 30's Docker window and Task 31's deployed-device gate remain blocked unless their required authority exists.
- Test decision: TDD + JUnit 5/Spring MockMvc/Vitest/Playwright; PostgreSQL/MinIO/Testcontainers/Compose integration only in Task 30's authorized isolated window, run serially.
- QA policy: every task has agent-executed scenarios; every source task captures RED before GREEN, then runs its narrow gate and a real HTTP/browser surface when available.
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/successor-01/<attempt-id>/`.
- Cleanup policy: feature tasks remove only their own loopback server/process and task worktree after evidence is retained; no task removes recovery worktrees or manual-stack containers/networks/volumes. Browser QA acquires one coordinator-owned `playwright-4173.lock`, uses only `127.0.0.1:4173`, and releases it after process termination.

## Execution strategy

### Successor baseline declaration

- Before Task 13, require `refs/heads/main=f4eae935874b7b84a980c19614cdcb7baffcea83`, ordered parents `[4e42ab26a9fddd8ba262bee2abcd7354304ec511,92842c7abbeac0ee157d0da085d6dcdde4e23c3c]`, and tree `9ebfe9863f3c00ec6cb49064c635b5c300ad8097`. Any mismatch stops execution and requires a new reviewed successor baseline; do not rebase this plan onto another tip.
- Set `PRIMARY_ROOT` to the canonical absolute primary discovery root before reading recovery material. Historical Todos 1-12 are complete and immutable. Recovery-02 stays an immutable failed recovery: `$PRIMARY_ROOT/.omo/evidence/recoveries/security-recovery-02/security-recovery-02-manifest.json` SHA-256 `c074f110d5bc919731d0f685e9fefe0b4de5b5c5b52f94430ca5f9244f24e740`, handoff SHA-256 `25e2483bf929019b625b6196860e8ef2e0e69770d231a7b1a10280699684215f`, and `$PRIMARY_ROOT/.omo/plans/narae-signing-web-mvp-security-recovery-02.md` SHA-256 `3e006ac7049a3ee9c561c56fd05a874641805daacd953e9aa502364872d8918b`.
- Policy A authorizes this successor to rely on semantic-content verification only, bound to those Recovery-02 artifacts and `$PRIMARY_ROOT/.omo/evidence/recoveries/security-recovery-03/task-1-recovery-03-correction-packet.json` SHA-256 `b2996ae0d620a1cae580c3953b9254ccc4654d29b26e9bcca642489c5b788218`. Limitation: this plan does **not** claim temporal one-shot writer proof; the packet records `writer_enforced_no_overwrite=false`, so only its verified semantic content is accepted.
- The current untracked `DESIGN.md` is adopted only if its SHA-256 remains `f201be513bd415119bc2a4e88ed1394bbee27173d450fce036b006c8cbb51ef3`; otherwise Task 13 stops for a reviewed design-contract update.
- Before the Task 13 branch starts, coordinator must use the official work-selection transition to create a distinct successor record referencing this plan, the exact baseline, Policy A, and all four Recovery digests: Recovery-02 plan/manifest/handoff plus the Recovery-03 correction packet. The mutable `.omo/boulder.json` selection may point to the successor only after its prior bytes/status are captured; it is runtime state, never staged or committed. The transition must retain Recovery-03 as predecessor with its original blocked/failed status in the retained runtime/history record; it must not check Recovery-03 boxes, mark it complete, overwrite its record, or reinterpret semantic verification as temporal proof. If the runtime cannot represent a distinct successor while preserving that predecessor, record `BLOCKED: successor work transition cannot preserve Recovery-03` and stop before Git/file mutation.
- Task 13 adopts `DESIGN.md` byte-identically before any visual implementation. It also commits this plan byte-identically. Evidence records the committed plan blob ID and SHA-256; all later tasks verify that committed snapshot and never flip plan checkboxes or edit plan bytes. Task status lives only in successor runtime/evidence records.
- Coordinator creates each branch in its own `/private/tmp/narae-successor-01-<task>-*/worktree`, from the exact integrated dependency tip. Branch workers never edit the primary discovery worktree or another task worktree.
- Before and after every task, record a read-only stable snapshot of listener `127.0.0.1:18083` and manual-stack container IDs, image IDs, mounts, networks, health, and restart counts. Stable fields must match; timestamps and uptime may differ.

### Cross-task contracts

- Visual contract: `DESIGN.md` supplies operational tokens only. OAuth/social-sign-in, marketing hero, decorative blob, trust-logo, booking-widget, marketing footer, and marketing copy patterns are explicitly non-product references and must not appear.
- Cache contract: authenticated admin/owner, share-token, signer-session, SSE, and final-PNG responses use `Cache-Control: no-store, private`; token-bearing HTML/API also uses `Referrer-Policy: no-referrer`. Static hashed assets may use normal immutable caching.
- Admin session contract: every `/api/v1/admin/**` request, including SSE and PNG, checks `AdminSessionContract.isCurrent` at request entry; expired sessions invalidate the cookie/session and return the existing stable auth response before owner lookup.
- Signer session contract: every signer-protected request, especially submission, checks `SignerSessionContract.isCurrent` for both idle and absolute lifetime before board/slot lookup; expiry invalidates the cookie/session and returns the existing generic signer response. A valid share link can start a new identify flow but never revive an expired signer session.
- Phone policy: signing support is `min(viewport width, viewport height) >= 600 CSS px`; both orientations of phones remain blocked, common 600px+ tablets pass. This is a support heuristic only, never authorization; backend ignores device/UA.
- Signature wire contract: JSON is `{ "version": 1, "strokes": [{ "points": [{ "x": int, "y": int }] }] }`; `x,y` are capture-time HALF_UP integers in `[0,1000000]`, consecutive duplicates are removed, each stroke has at least one point, maximum 128 strokes/4096 total points/1,048,576 request bytes. No time, pressure, color, width, identity, or raster data crosses the wire. Rendering uses black round-cap/round-join paths, maps x/y independently into current slot pixels, and uses fixed width `max(1, HALF_UP(min(slotPixelWidth,slotPixelHeight)*0.012))`; pad preview and Java2D renderer share golden vectors.
- Submission invariant: under board-then-slot locks, first-wins requires `roster_entry.submitted=false`, `signature_slot.encrypted_strokes IS NULL`, and matching current link/revision/aspect. One transaction writes encrypted strokes, nonce/version, `submitted_at`, and `roster_entry.submitted=true`; any pre-existing split state fails closed as `signature_state_invalid` without repair.
- Roster contract: bulk `PUT /roster` JSON and `POST /roster/import` multipart are server-parsed and `DRAFT`-only. During `OPEN`, direct POST/PATCH/DELETE remains allowed for unsubmitted people; completed people require signature reset first. All state checks occur in the locked transaction.
- Rate-limit contract: identify uses token buckets keyed by `(share_token_lookup_hash,trusted_client_ip)` capacity 60/refill 1 request/sec plus IP capacity 120/refill 2/sec; submit uses pair capacity 30/refill 0.5/sec plus IP capacity 60/refill 1/sec. Entries expire after 30 idle minutes, storage caps at 10,000 entries with oldest-idle eviction, and denial returns bounded 429/`Retry-After`. A deterministic 25-signer NAT burst must pass.
- Migration/dependency contract: Task 16 exclusively adds pinned Apache POI. Task 17 starts after Task 16, takes explicit `backend/build.gradle.kts` handoff, and adds one pinned EXIF metadata reader. Task 28 exclusively owns forward migration `V4__deletion_job_lease.sql`; no other task creates a migration.
- Browser/Docker contract: Tasks 14, 18, 22, 23, 25, and 26 acquire the same Playwright-port lock, start one Vite process, and terminate it before release. Task 30 alone may suspend the manual stack and run Testcontainers then Compose, strictly sequentially; its before/after evidence is sole authority for Docker/manual-stack preservation. F2/F4 cannot approve Docker claims without Task 30's passed evidence.

### Frontend conflict policy

- Task 14 runs in its own `/private/tmp/narae-successor-01-14-*/worktree` from the integrated Task 13 tip. Until its one commit is integrated, no other frontend task starts. Tasks 18, 22, 23, 25, 26, and 27 consume that integrated tip and never edit Task 14's shell/style/component files.
- Package/entry ownership is serial: Task 14 may edit `frontend/package.json`, `frontend/package-lock.json`, and `frontend/src/main.tsx` only for the accepted design-tooling gate. After Task 14 integrates, `main.tsx` freezes; Task 23 alone receives package/lockfile ownership for a proven QR dependency. No other task edits those three paths.
- Route ownership is serial: Task 18 uses its standalone test harness and never edits a product route. Task 22 alone edits `AppRouter.tsx` and creates `FullViewRoute.tsx`/`PublicSignerRoute.tsx`; after integration, Task 25 alone receives `FullViewRoute.tsx` and Task 26 alone receives `PublicSignerRoute.tsx`. No later task edits `AppRouter.tsx`.
- Editor ownership is serial: Task 23 establishes `frontend/src/features/boards/editor/**`; after its integration, Task 25 may add only `RealtimeBoardBridge.tsx`, Task 27 may add only `FinalPngAction.tsx`, and Task 28 starts after Tasks 25/27 and may add only `DeleteBoardAction.tsx` there.
- Any branch diff containing a path owned by another active task fails before commit. Resolve by waiting for the owner to integrate and restarting from that dependency tip; do not cherry-pick overlapping parallel edits or resolve them opportunistically during merge. All browser QA uses the shared lock, so parallel code work never means parallel port-4173 runs.

### File ownership

| Task | Branch | Exclusive files while active |
|------|--------|------------------------------|
| 13 | `docs/successor-01-baseline` | `.omo/plans/narae-signing-web-mvp-successor-01.md`, `DESIGN.md` |
| 14 | `feat/frontend-design-foundation` | only `frontend/src/styles.css`, `frontend/src/components/AppShell.tsx`, `frontend/src/routes/LoginRoute.tsx`, `frontend/src/routes/RouteShell.tsx`, `frontend/src/components/AsyncViews.tsx`, `frontend/src/components/UnsupportedDeviceView.tsx`, focused visual tests/evidence; accepted React tooling may also own `frontend/package.json`, `frontend/package-lock.json`, `frontend/src/main.tsx` |
| 15 | `feat/board-core` | `backend/src/main/java/com/naraesigning/board/core/**`, matching unit tests, board/share/status corrections in `docs/plan/IMPLEMENTATION_PLAN.md` |
| 16 | `feat/roster-import` | `backend/src/main/java/com/naraesigning/roster/**`, matching tests, `backend/build.gradle.kts`, roster/import corrections in `docs/plan/IMPLEMENTATION_PLAN.md` |
| 17 | `feat/background-assets` | `backend/src/main/java/com/naraesigning/background/**`, matching tests, post-Task-16 `backend/build.gradle.kts` handoff |
| 18 | `feat/signature-pad` | `frontend/src/features/signing/pad/**`, `frontend/e2e/harness/signature-pad.html`, `frontend/e2e/signature-pad.spec.ts` |
| 19 | `feat/slot-layout` | `backend/src/main/java/com/naraesigning/slot/**`, matching tests |
| 20 | `feat/admin-board-api` | `backend/src/main/java/com/naraesigning/board/api/**`, matching tests |
| 21 | `feat/public-identify` | `backend/src/main/java/com/naraesigning/signer/identify/**`, matching tests |
| 22 | `feat/admin-board-ui` | `frontend/src/features/boards/list/**`, `frontend/src/features/boards/roster/**`, route modules and `AppRouter.tsx`, matching tests/e2e |
| 23 | `feat/admin-canvas-ui` | `frontend/src/features/boards/editor/**`, `frontend/src/features/boards/background/**`, `frontend/src/features/boards/share/**`, `frontend/package*.json`, matching tests/e2e |
| 24 | `feat/signature-submit` | `backend/src/main/java/com/naraesigning/signature/**`, `frontend/src/features/signing/api/**`, matching tests |
| 25 | `feat/realtime-fullview` | `backend/src/main/java/com/naraesigning/realtime/**`, `frontend/src/features/boards/fullview/**`, `frontend/src/routes/FullViewRoute.tsx`, post-Task-23 `frontend/src/features/boards/editor/RealtimeBoardBridge.tsx`, matching tests/e2e |
| 26 | `feat/public-signer-ui` | `frontend/src/features/signing/flow/**`, `frontend/src/routes/PublicSignerRoute.tsx`, `frontend/src/routes/useTabletSupport.ts`, matching tests/e2e |
| 27 | `feat/final-png` | `backend/src/main/java/com/naraesigning/render/**`, post-Task-23 `frontend/src/features/boards/editor/FinalPngAction.tsx`, matching tests |
| 28 | `feat/board-deletion` | `backend/src/main/java/com/naraesigning/deletion/**`, `backend/src/main/resources/db/migration/V4__deletion_job_lease.sql`, post-Task-25 `frontend/src/features/boards/editor/DeleteBoardAction.tsx`, matching tests |
| 29 | `ci/release-delivery` | `.github/workflows/release.yml`, `scripts/release/**`, `infra/portainer/**`, `docs/qa/MVP_TABLET_RELEASE_CHECKLIST.md`, `docs/REVERSE_PROXY_SETUP_GUIDE.md`, release-only tests/docs |
| 30 | `test/mvp-verification` | `backend/src/integrationTest/java/com/naraesigning/mvp/**`, `frontend/e2e/mvp-flow.spec.ts`, `scripts/verify-mvp.sh`, test-only fixtures |
| 31 | none | evidence only; no repository file |

### Parallel execution waves
> Target 5-8 tasks per wave where dependencies allow. This repository has serial shared domain seams; smaller waves prevent file and schema collisions.

Wave 1 (baseline):
- Task 13: successor baseline commit

Wave 2 (design and disjoint first capabilities):
- Task 14: depends [13]
- Task 15: depends [13]
- Task 29: depends [13]

Wave 3 (disjoint data capabilities):
- Task 16: depends [15]
- Task 18: depends [14]

Wave 4 (background and shared slot invariant):
- Task 17: depends [15, 16]
- Task 19: depends [15, 16]

Wave 5 (parallel API surfaces):
- Task 20: depends [15, 16, 19]
- Task 21: depends [15, 16, 19]

Wave 6 (disjoint admin and signer clients):
- Task 22: depends [14, 18, 20]
- Task 24: depends [18, 19, 21]

Wave 7 (disjoint user surfaces):
- Task 23: depends [14, 16, 17, 19, 20, 22]
- Task 26: depends [14, 18, 21, 22, 24]

Wave 8 (disjoint manager outcome surfaces):
- Task 25: depends [14, 20, 22, 23, 24]
- Task 27: depends [17, 19, 22, 23, 24]

Wave 9 (serialized destructive path):
- Task 28: depends [15, 17, 19, 20, 23, 24, 25, 27]

Wave 10 (authorized integration):
- Task 30: depends [25, 26, 27, 28, 29]

Wave 11 (external deployed-device gate):
- Task 31: depends [30]

Critical path: Task 13 -> Task 15 -> Task 16 -> Task 19 -> Task 20 -> Task 22 -> Task 23 -> Task 25 -> Task 28 -> Task 30 -> Task 31

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 13 | none | 14-30 | none |
| 14 | 13 | 18, 22, 23, 25, 26, 30 | 15, 29 |
| 15 | 13 | 16, 17, 19-21, 28 | 14, 29 |
| 16 | 15 | 17, 19-23 | 14, 18, 29 |
| 17 | 15, 16 | 23, 27, 28 | 18, 19, 29 |
| 18 | 14 | 22, 24, 26 | 16, 17, 29 |
| 19 | 15, 16 | 20, 21, 23, 24, 27, 28 | 17, 18, 29 |
| 20 | 15, 16, 19 | 22, 23, 25, 28 | 21, 29 |
| 21 | 15, 16, 19 | 24, 26 | 20, 29 |
| 22 | 14, 18, 20 | 23, 25-27 | 24, 29 |
| 23 | 14, 16, 17, 19, 20, 22 | 25, 27, 28 | 24, 26, 29 |
| 24 | 18, 19, 21 | 25-28 | 22, 23, 29 |
| 25 | 14, 20, 22, 23, 24 | 28, 30 | 26, 27, 29 |
| 26 | 14, 18, 21, 22, 24 | 30 | 23, 25, 27, 29 |
| 27 | 17, 19, 22, 23, 24 | 28, 30 | 25, 26, 29 |
| 28 | 15, 17, 19, 20, 23, 24, 25, 27 | 30 | 26, 29 |
| 29 | 13 | 30 | 14-28 |
| 30 | 25, 26, 27, 28, 29 | 31, F1-F4 | none |
| 31 | 30 | F1-F4 | none |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 13. Establish and commit the successor baseline

  What to do: First create the distinct successor runtime selection described above and prove it preserves Recovery-03's blocked/failed predecessor state. From a clean task-owned worktree at the exact declared `main`, verify commit/parents/tree and the Recovery-02/03 digests. Copy the exact hashed `DESIGN.md` from `PRIMARY_ROOT` byte-for-byte first, then copy this exact plan snapshot, review the complete staged diff, and commit both before any product branch starts. Record primary dirty state without modifying it; record the committed plan blob ID and SHA-256 as the immutable execution snapshot.
  Must NOT do: Do not import `.omo/evidence`, Recovery plans, Boulder/start-work files, or any other untracked path. Do not claim temporal one-shot writer proof.

  Parallelization: Can parallel: NO | Wave 1 | Blocks: [14-30] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main^{commit}` at `f4eae935874b7b84a980c19614cdcb7baffcea83` - successor source baseline.
  - API/Type: `$PRIMARY_ROOT/.omo/plans/narae-signing-web-mvp-security-recovery-02.md:1`, `$PRIMARY_ROOT/.omo/evidence/recoveries/security-recovery-02/security-recovery-02-manifest.json:1`, and `$PRIMARY_ROOT/.omo/evidence/recoveries/security-recovery-02/security-recovery-02-handoff.json:1` - immutable failed-recovery semantics.
  - Test:     `$PRIMARY_ROOT/.omo/evidence/recoveries/security-recovery-03/task-1-recovery-03-correction-packet.json:1` - Policy-A semantic supplement and writer limitation.
  - External: `$PRIMARY_ROOT/DESIGN.md:8-24,59-119,121-202,258-428` - byte-identical source; Task 14 may operationalize tokens only.

  Acceptance criteria (agent-executable only):
  - [ ] `test "$(git rev-parse refs/heads/main)" = f4eae935874b7b84a980c19614cdcb7baffcea83 && test "$(git show -s --format=%P main)" = '4e42ab26a9fddd8ba262bee2abcd7354304ec511 92842c7abbeac0ee157d0da085d6dcdde4e23c3c' && test "$(git rev-parse main^{tree})" = 9ebfe9863f3c00ec6cb49064c635b5c300ad8097` exits 0.
  - [ ] The successor work selection exists as a new record, references the exact baseline/plan/Policy-A digests, and a before/after query proves Recovery-03 remains blocked/failed and byte-identical; an incapable runtime produces the exact blocker and no Git/file mutation.
  - [ ] SHA-256 checks for all four Recovery artifacts and `DESIGN.md` equal the baseline declaration; `cmp -s "$PRIMARY_ROOT/DESIGN.md" DESIGN.md` succeeds; staged paths equal exactly `.omo/plans/narae-signing-web-mvp-successor-01.md` and `DESIGN.md`.
  - [ ] Commit exists on `docs/successor-01-baseline`, has exact subject, and is merged to `main` with `--no-ff`; evidence records `git rev-parse HEAD:.omo/plans/narae-signing-web-mvp-successor-01.md` and SHA-256; primary discovery bytes outside this newly created plan remain unchanged.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Exact successor baseline is adopted
    Tool:     bash
    Steps:    Query predecessor/successor work records, run the three Git assertions, `shasum -a 256` and `cmp -s` checks, `git diff --cached --name-only`, commit, record plan blob/SHA, then `git show --stat --oneline HEAD`.
    Expected: Recovery-03 remains blocked/failed and unchanged; all hashes match; two files only; one atomic commit and one no-ff merge exist.
    Evidence: <attemptDir>/task-13-successor-baseline.log

  Scenario: Recovery or design drift fails closed
    Tool:     bash
    Steps:    In a disposable copy, alter one expected digest and add one staged evidence path; run the baseline/staged-path gate.
    Expected: Both variants exit nonzero; real recovery artifacts and primary worktree remain unchanged.
    Evidence: <attemptDir>/task-13-successor-baseline-error.log
  ```

  Commit: YES | Message: `docs(plan): establish successor MVP baseline` | Files: [`.omo/plans/narae-signing-web-mvp-successor-01.md`, `DESIGN.md`]

- [ ] 14. Establish the frontend visual foundation

  What to do: On `feat/frontend-design-foundation`, consume the byte-identical Task 13 `DESIGN.md` snapshot and operationalize only its color, typography, spacing, radius, elevation, focus, and responsive tokens. Restyle the existing app shell, login, route shell, async states, and unsupported-device state without changing route ownership or product behavior. Add focused component/visual tests and real-browser screenshots at 375x812, 768x1024, and 1280x800. Mandatory tooling gate: resolve and pin React-19/Vite-8-compatible releases of `react-grab`, `react-scan`, and `react-doctor` from package metadata; `react-grab`/`react-scan` may initialize only behind `import.meta.env.DEV`, and `react-doctor` remains a development CLI. If compatibility, lockfile determinism, production-tree-shaking, or tests cannot be proved, record `BLOCKED: mandatory React design tooling is incompatible` and do not start Tasks 18, 22, 23, 25, or 26. Task 14 alone owns the allowed package/entry files for this decision.
  Must NOT do: Touch `frontend/src/routes/AppRouter.tsx`, `frontend/src/routes/PublicSignerRoute.tsx`, any `frontend/src/features/**` path, or product/API behavior. Do not implement OAuth/social sign-in, marketing hero/blob/trust-logo/booking/footer components, marketing copy, a new component library, or literal screenshot imitation. Do not add tooling to the production bundle or let later parallel work edit Task 14-owned files.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [18, 22, 23, 25, 26, 30] | Blocked by: [13]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/styles.css:1-249`, `main:frontend/src/components/AppShell.tsx:1-38`, and `main:frontend/src/routes/LoginRoute.tsx:1-92` - current styling and shell seams.
  - API/Type: `main:frontend/src/routes/RouteShell.tsx:1-51`, `main:frontend/src/components/AsyncViews.tsx:1-47`, and `main:frontend/src/components/UnsupportedDeviceView.tsx:1-45` - existing route/loading/error/device contracts; behavior and accessible names stay stable.
  - Test:     `main:frontend/src/test/AppRouter.test.tsx:1-130` and `main:frontend/playwright.config.ts:1-20` - component/browser test patterns; add `frontend/src/test/DesignFoundation.test.tsx` and `frontend/e2e/design-foundation.spec.ts` without editing router code.
  - External: `DESIGN.md:8-24,59-119,121-202,258-428` and `https://registry.npmjs.org/react-grab`, `https://registry.npmjs.org/react-scan`, `https://registry.npmjs.org/react-doctor` - operational token source plus authoritative compatibility metadata; all marketing/OAuth examples are excluded.

  Acceptance criteria (agent-executable only):
  - [ ] Before implementation, focused visual/component assertions fail and the log is retained; after implementation `cd frontend && npm ci && npm run lint && npm run typecheck && npm run test -- --run src/test/DesignFoundation.test.tsx && npm run build` exits 0. `npm run lint` is the repository's semantic Biome gate; do not substitute `npm exec biome lint .`.
  - [ ] A production-build inspection proves `react-grab`, `react-scan`, and their initialization strings are absent from emitted production chunks; `npm ls --depth=0` and lockfile diff prove exact compatible pins. If that proof fails, the task is blocked before dependent frontend tasks.
  - [ ] `git diff --name-only <base>..HEAD` is a subset of `frontend/src/styles.css`, `frontend/src/components/AppShell.tsx`, `frontend/src/routes/LoginRoute.tsx`, `frontend/src/routes/RouteShell.tsx`, `frontend/src/components/AsyncViews.tsx`, `frontend/src/components/UnsupportedDeviceView.tsx`, `frontend/src/test/DesignFoundation.test.tsx`, `frontend/e2e/design-foundation.spec.ts`, and, only after the tooling gate passes, `frontend/package.json`, `frontend/package-lock.json`, `frontend/src/main.tsx`.
  - [ ] Static assertions and screenshots prove every used visual value resolves through the adopted tokens, keyboard focus is visible, reduced motion is respected, Korean copy is unchanged, and no excluded marketing/OAuth element exists.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Product shells use one responsive visual system
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/design-foundation.spec.ts --project=chromium`. The existing Playwright `webServer` config is the sole Vite owner and starts `npm run dev -- --host 127.0.0.1 --port 4173`; the spec covers 375x812, 768x1024, and 1280x800 and keyboard-tabs login, loading, error, and unsupported-device states. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Token-derived typography/color/spacing/radius/elevation render consistently; focus is visible; no overflow, marketing/OAuth UI, console error, or network request from design tooling.
    Evidence: <attemptDir>/task-14-design-foundation.zip

  Scenario: Production and tooling boundaries fail closed
    Tool:     bash
    Steps:    Run the compatibility/pin check, production build string/import scan, and an ownership diff; in a disposable fixture enable one tool outside `import.meta.env.DEV` and add one edit to `AppRouter.tsx`.
    Expected: Real branch passes; each fixture exits nonzero; production chunks contain no design-tool code and forbidden paths remain untouched.
    Evidence: <attemptDir>/task-14-design-foundation-error.log
  ```

  Commit: YES | Message: `feat(frontend): establish product design foundation` | Files: [`frontend/src/styles.css`, `frontend/src/components/AppShell.tsx`, `frontend/src/routes/LoginRoute.tsx`, `frontend/src/routes/RouteShell.tsx`, `frontend/src/components/AsyncViews.tsx`, `frontend/src/components/UnsupportedDeviceView.tsx`, `frontend/src/test/DesignFoundation.test.tsx`, `frontend/e2e/design-foundation.spec.ts`, optional accepted `frontend/package.json`, `frontend/package-lock.json`, `frontend/src/main.tsx`]

- [ ] 15. Implement owner-scoped board core and private share identity

  What to do: Add JDBC repository/service records for create/list/detail/title, internal lifecycle, and encrypted current share token. Reuse `VersionedCryptoService`, `CryptoContext.shareToken`, 32 random bytes encoded base64url without padding, SHA-256 lookup, owner ID from `AdminSessionContract`, and transactional reissue that increments `share_link_version`. Map database `DRAFT/OPEN` to product setup/signing labels at the API boundary. Correct only implementation-plan share-token/status text that contradicts current schema.
  Must NOT do: No client owner/status input, raw token in list/detail/log, non-owner distinction, new ORM, or schema migration unless a failing test proves V1 cannot support the contract.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [16, 17, 19, 20, 21, 28] | Blocked by: [13]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/java/com/naraesigning/auth/AuthService.java:14-57` - JDBC service and transaction style.
  - API/Type: `main:backend/src/main/resources/db/migration/V1__core_schema.sql:10-26` and `main:backend/src/main/java/com/naraesigning/crypto/CryptoContext.java:20-25` - existing board/share storage and AAD.
  - Test:     `main:backend/src/test/java/com/naraesigning/auth/LoginApiTest.java:1` - Spring/JDBC test idiom.
  - External: `docs/plan/PRODUCT_PLAN.md:28-58` and `docs/plan/IMPLEMENTATION_PLAN.md:251-274,294-306,344-353` - lifecycle and API contract.

  Acceptance criteria (agent-executable only):
  - [ ] RED log proves owner isolation or reissue behavior failed before implementation; `cd backend && ./gradlew test --tests '*BoardCore*'` then exits 0 without Docker.
  - [ ] Tests prove 1-120 Unicode-code-point title, owner-only list/detail, `DELETING` exclusion, hash-only public lookup, authenticated current-token decryption, old-token invalidation, and no raw token in logs/list/detail.
  - [ ] `git diff --name-only <base>..HEAD` stays inside Task 15 ownership; no migration is added.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Owner creates and reissues one board
    Tool:     bash
    Steps:    Run `cd backend && ./gradlew test --tests '*BoardCore*' --info` with synthetic owner A; inspect sanitized assertions for create, current share retrieval, reissue, and old hash miss.
    Expected: New token differs; version increments once; only owner A decrypts current token.
    Evidence: <attemptDir>/task-15-board-core.log

  Scenario: Enumeration and plaintext probes fail
    Tool:     bash
    Steps:    Run owner-B UUID lookup, invalid/old token lookup, 121-code-point title, and captured-log plaintext tests.
    Expected: Generic unavailable/validation outcomes; zero title, owner, raw token, ciphertext metadata, or secret in response/log.
    Evidence: <attemptDir>/task-15-board-core-error.log
  ```

  Commit: YES | Message: `feat(board): add owner-scoped board core` | Files: [`backend/src/main/java/com/naraesigning/board/core/**`, `backend/src/test/java/com/naraesigning/board/core/**`, `docs/plan/IMPLEMENTATION_PLAN.md`]

- [ ] 16. Implement exact roster editing and bounded all-or-nothing import

  What to do: Add direct create/update/delete services plus bulk replacement. Parse pasted rows and `PUT /roster` JSON, UTF-8 comma CSV and exactly-one-sheet XLSX through multipart `POST /roster/import`, all at the server boundary. Preserve accepted organization/job/name bytes exactly; reject blank name, duplicates, extra/missing fields, bad headers, BOM/encoding/delimiter, second sheet, over 50 rows, oversized file/body, and zip bombs. Encrypt identity, compute board-bound HMAC, create one unplaced slot, and replace bulk input atomically in `DRAFT`, retaining unchanged byte-exact identities and slot state. Direct CRUD is permitted in `DRAFT`; in `OPEN` it is permitted only for unsubmitted people, while submitted people require reset first. Update roster/import sections of `docs/plan/IMPLEMENTATION_PLAN.md` to state those exact server-side verbs and lifecycle rules.
  Must NOT do: No client-authoritative parser, partial success, echoed raw rows/parser errors, trimming/case folding/Unicode normalization, stored source file, or dependency beyond pinned Apache POI needed for XLSX.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [17, 19, 20, 21, 22, 23] | Blocked by: [15]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/java/com/naraesigning/web/BodyLimitFilter.java:1-106` and `BufferedMultipartRequest.java:1-101` - bounded-body seam.
  - API/Type: `main:backend/src/main/resources/db/migration/V1__core_schema.sql:28-65` and `VersionedCryptoService.java:96-107` - encrypted identity/HMAC/slot storage.
  - Test:     `main:backend/src/test/java/com/naraesigning/web/MultipartBodyLimitHttpTest.java:1-128` - HTTP size-boundary test pattern.
  - External: `https://poi.apache.org/components/configuration.html` and `https://poi.apache.org/components/spreadsheet/quick-guide.html` - zip safety and workbook lifecycle.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes implementation; `cd backend && ./gradlew test --tests '*Roster*'` exits 0 without Testcontainers.
  - [ ] Tests cover exact headers `소속사,직책,이름`, inclusive 1,048,576-byte part/raw-body limits, 1,065,000-byte multipart limit, 50/51 rows, deterministic bounded errors, one sheet, zip-bomb rejection, exact HMAC, and full rollback.
  - [ ] Tests cover `PUT /roster` and multipart `POST /roster/import` as `DRAFT`-only bulk paths plus direct POST/PATCH/DELETE in `OPEN` for unsubmitted people; submitted-person mutation rejects until reset. Documentation states the same verbs/rules.
  - [ ] Apache POI is version-pinned in Task 16's exclusive `backend/build.gradle.kts` edit; every workbook/package/input stream closes; parser-entry counters remain zero for oversized input.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Equivalent CSV, XLSX, and paste replace roster
    Tool:     bash
    Steps:    Run targeted MockMvc tests with a synthetic 25-row roster containing empty optional fields, then compare decrypted test-only triples and retained UUID/slot state.
    Expected: All three paths yield identical exact triples and one unplaced slot per new person.
    Evidence: <attemptDir>/task-16-roster-import.log

  Scenario: Malformed bulk input changes nothing
    Tool:     bash
    Steps:    Run fixtures for BOM/semicolon CSV, second-sheet XLSX, zip bomb, extra part/field, duplicate, blank name, 51 rows, and persistence failure.
    Expected: Stable safe codes in input order, bounded error count, prior roster/slot graph unchanged, no plaintext log.
    Evidence: <attemptDir>/task-16-roster-import-error.log
  ```

  Commit: YES | Message: `feat(roster): add exact bounded roster import` | Files: [`backend/build.gradle.kts`, `backend/src/main/java/com/naraesigning/roster/**`, `backend/src/test/java/com/naraesigning/roster/**`, `docs/plan/IMPLEMENTATION_PLAN.md`]

- [ ] 17. Implement encrypted background asset lifecycle

  What to do: After Task 16 integrates, take explicit ownership handoff of `backend/build.gradle.kts` and add one pinned EXIF metadata reader; do not hand-parse EXIF. Validate actual PNG/JPEG bytes, decode within 50 MiB and 12,000px-per-axis bounds, apply JPEG orientation, strip metadata by re-encoding, encrypt normalized bytes, store only ciphertext in private MinIO, and encrypt object keys in PostgreSQL. Support same-canvas replacement with contain/white letterbox plus explicitly confirmed background-ratio adoption. In the same database transaction that swaps the asset pointer, insert the encrypted durable object-cleanup job; only after commit may the scheduled cleanup delete the old object.
  Must NOT do: No PDF, direct/public/presigned object URL, plaintext temp file, unbounded decode, or background replacement that silently changes canvas ratio.

  Parallelization: Can parallel: YES | Wave 4 | Blocks: [23, 27, 28] | Blocked by: [15, 16]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/java/com/naraesigning/config/BackendConfiguration.java:13-23` - existing bounded MinIO client.
  - API/Type: `main:backend/src/main/resources/db/migration/V1__core_schema.sql:67-97` - asset and durable cleanup tables.
  - Test:     `main:backend/src/test/java/com/naraesigning/web/BodyLimitFilterTest.java:1-187` - pre-parser rejection pattern.
  - External: `docs/plan/PRODUCT_PLAN.md:60-67` and `docs/plan/IMPLEMENTATION_PLAN.md:324-342` - background and image limits.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd backend && ./gradlew test --tests '*Background*'` exits 0 using an in-memory object-store fake, not Docker.
  - [ ] Tests prove magic-byte/decode validation, 50 MiB and dimension boundaries, EXIF orientations 1-8 through the pinned library, metadata removal, ciphertext-only object bytes, encrypted object key, ordinary replacement geometry, and confirmed ratio adoption.
  - [ ] A transaction test proves pointer replacement and encrypted cleanup-job insertion commit or roll back together; a post-commit worker deletes only after commit, survives restart/retry, and never deletes the new/current object.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Valid rotated JPEG becomes encrypted normalized background
    Tool:     bash
    Steps:    Run `./gradlew test --tests '*Background*'` with generated EXIF-rotated JPEG and transparent PNG fixtures.
    Expected: Canonical dimensions/orientation match; stored bytes are not image-decodable before decryption; response contains no object key.
    Evidence: <attemptDir>/task-17-background-assets.log

  Scenario: Bomb and partial-store paths leave prior background intact
    Tool:     bash
    Steps:    Run oversized dimension, corrupt bytes, mismatched MIME, fake-store failure, and unconfirmed ratio-adoption tests.
    Expected: Stable rejection; previous asset/canvas remains; no orphan database pointer or plaintext temp file.
    Evidence: <attemptDir>/task-17-background-assets-error.log
  ```

  Commit: YES | Message: `feat(background): add encrypted image lifecycle` | Files: [`backend/build.gradle.kts`, `backend/src/main/java/com/naraesigning/background/**`, `backend/src/test/java/com/naraesigning/background/**`]

- [ ] 18. Implement deterministic tablet pointer capture

  What to do: Build one native canvas component using Pointer Events, pointer capture, CSS `touch-action: none`, device-pixel-ratio rendering, the exact cross-task signature wire/render contract, clear-all, and bounded serialization. Preserve strokes after submit failure and clear only on user clear or confirmed success. Add a test-only Vite HTML harness at `/e2e/harness/signature-pad.html`; it imports only the pad and never edits or mounts through `AppRouter.tsx` or `PublicSignerRoute.tsx`.
  Must NOT do: No drawing library, undo, color/width controls, image snapshot payload, mouse-only logic, or render-time mutation.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [22, 24, 26] | Blocked by: [14]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/routes/useTabletSupport.ts:1-14` - current device-policy hook.
  - API/Type: `main:frontend/src/routes/AppRouter.tsx:16-28` - existing signer canvas seam.
  - Test:     `main:frontend/src/test/AppRouter.test.tsx:1-96` - React testing style.
  - External: `https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events/Using_Pointer_Events` and `https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/touch-action` - pointer/canvas contract.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd frontend && npm run test -- --run src/features/signing/pad` and `npm run typecheck` exit 0.
  - [ ] Tests prove pen/touch/mouse pointer capture, DPR resizing without stroke loss, integer `[0,1000000]` HALF_UP normalization, duplicate removal, 128-stroke/4096-point/1,048,576-byte limits, exact preview golden vectors, clear-all, cancelled pointer cleanup, and failure retention.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Pen and touch draw the same normalized signature
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/signature-pad.spec.ts --project=chromium`. The existing Playwright `webServer` config is the sole Vite owner; the spec opens `/e2e/harness/signature-pad.html`, dispatches concrete pen and touch pointer sequences on `[data-testid=signer-canvas]`, resizes the viewport, and inspects rendered/payload points. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Visible continuous black round-cap strokes match golden pixels; payload coordinates are exact integers in `[0,1000000]`; clear removes all.
    Evidence: <attemptDir>/task-18-signature-pad.png

  Scenario: Cancellation and payload flood stay bounded
    Tool:     playwright(real Chrome)
    Steps:    Dispatch `pointercancel`, then points beyond the cap; simulate rejected submit.
    Expected: Capture releases, UI stays responsive, stable too-complex state appears, pre-failure strokes remain.
    Evidence: <attemptDir>/task-18-signature-pad-error.png
  ```

  Commit: YES | Message: `feat(signing): add tablet pointer signature pad` | Files: [`frontend/src/features/signing/pad/**`, `frontend/e2e/harness/signature-pad.html`, `frontend/e2e/signature-pad.spec.ts`]

- [ ] 19. Implement normalized slots, revision invalidation, and serialized mutation rules

  What to do: Add slot domain/repository/service with placed/unplaced state, normalized bounds, transparent/white background, touching-edge allowance, overlap rejection, canonical aspect rounded HALF_UP to six decimals, board-first then ascending-slot lock order, and revision changes on reset/delete/reassign/unsubmitted identity edit but not visual move/resize.
  Must NOT do: No client-authoritative collision result, floating comparison without canonical rounding, optimistic database write before full prospective-layout validation, or alternate lock order.

  Parallelization: Can parallel: YES | Wave 4 | Blocks: [20, 21, 23, 24, 27, 28] | Blocked by: [15, 16]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/resources/db/migration/V1__core_schema.sql:42-65` - existing slot constraints.
  - API/Type: `main:backend/src/main/java/com/naraesigning/session/SignerSessionContract.java:35-66` - revision/aspect invalidation contract.
  - Test:     `main:backend/src/test/java/com/naraesigning/session/InvalidationSessionContractTest.java:1-90` - invalidation matrix style.
  - External: `docs/plan/PRODUCT_PLAN.md:87-104` and `docs/plan/IMPLEMENTATION_PLAN.md:150-163,292-306` - placement/operations rules.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd backend && ./gradlew test --tests '*Slot*'` exits 0 without Docker.
  - [ ] Unit tests cover every boundary, overlap/touch, `(0,1000]` aspect, deterministic lock-order call trace, revision matrix, reset/delete outcomes, and rollback on conflict.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Place and move 25 non-overlapping slots
    Tool:     bash
    Steps:    Run targeted slot service tests with 25 synthetic UUIDs, touching edges, move/resize, reset, and delete.
    Expected: Valid layout persists; visual moves preserve revision; reset/delete increment once and clear signature per rule.
    Evidence: <attemptDir>/task-19-slot-layout.log

  Scenario: Conflicting prospective writes have one winner
    Tool:     bash
    Steps:    Run deterministic repository-fake barrier tests for two overlapping writes and reset versus stale submit.
    Expected: One success, one stable conflict, canonical board-then-slot trace, unchanged losing state.
    Evidence: <attemptDir>/task-19-slot-layout-error.log
  ```

  Commit: YES | Message: `feat(slot): enforce normalized serialized layout` | Files: [`backend/src/main/java/com/naraesigning/slot/**`, `backend/src/test/java/com/naraesigning/slot/**`]

- [ ] 20. Expose owner-only administrator board APIs

  What to do: Add authenticated MockMvc controllers for board/list/detail/title, roster direct CRUD, `PUT /roster`, multipart `POST /roster/import`, slots, reset, share/reissue, background, and open/close/reopen. At entry to every `/api/v1/admin/**` request, call `AdminSessionContract.isCurrent`; invalidate an expired cookie/session and return the existing stable auth response before owner lookup. Resolve owner exclusively from the current session, verify CSRF through the existing filter, enforce lifecycle inside service transactions, return stable codes and the cross-task cache headers, and publish domain events only after commit.
  Must NOT do: No public routes, client owner/status, raw token in board detail, controller-owned transaction logic, or placeholder final-PNG/deletion/SSE behavior.

  Parallelization: Can parallel: YES | Wave 5 | Blocks: [22, 23, 25, 28] | Blocked by: [15, 16, 19]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/java/com/naraesigning/auth/AuthController.java:19-95` and `AuthApiAdvice.java:1-20` - controller/advice style.
  - API/Type: `main:backend/src/main/java/com/naraesigning/session/AdminSessionContract.java:9-25` - authenticated owner source.
  - Test:     `main:backend/src/test/java/com/naraesigning/auth/LoginApiTest.java:1-452` - MockMvc/session/CSRF coverage.
  - External: `docs/plan/IMPLEMENTATION_PLAN.md:224-274` - API family and preconditions.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd backend && ./gradlew test --tests '*BoardAdminApi*'` exits 0 without Docker.
  - [ ] Tests cover every route and verb, owner isolation, CSRF, absolute-session expiry before owner lookup, open prerequisites, OPEN direct-unsubmitted CRUD versus DRAFT-only bulk import, completed-signer edit/reset rule, safe cache/referrer headers on success/error, and exact unchanged state after rejected mutation.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Full owner lifecycle succeeds through HTTP
    Tool:     bash
    Steps:    Run MockMvc flow: authenticated owner create -> roster -> slots -> open -> close -> reopen -> share reissue.
    Expected: Status transitions and snapshots match server state; old link becomes invalid; every private response is no-store.
    Evidence: <attemptDir>/task-20-admin-api.log

  Scenario: Forged owner and stale lifecycle mutation fail
    Tool:     bash
    Steps:    Repeat with owner B, missing CSRF, incomplete open prerequisites, bulk import during OPEN, and submitted-person edit.
    Expected: Stable 401/403/409/validation codes; database state unchanged; no protected value disclosed.
    Evidence: <attemptDir>/task-20-admin-api-error.log
  ```

  Commit: YES | Message: `feat(board): expose administrator board APIs` | Files: [`backend/src/main/java/com/naraesigning/board/api/**`, `backend/src/test/java/com/naraesigning/board/api/**`]

- [ ] 21. Implement public link state, exact identification, and NAT-safe limiting

  What to do: Add minimal public link state and exact identify endpoints. Resolve raw token only by SHA-256 hash, require current `OPEN`, exact identity HMAC, placed/unsubmitted slot, issue signer session with board/slot/link version/revision/aspect, and implement the exact cross-task pair/IP token buckets, idle expiry, storage cap, eviction, trusted-proxy IP derivation, and bounded 429 response.
  Must NOT do: No roster enumeration, per-person lockout, token/session in browser storage, raw identity in session/log, Origin as authorization replacement, or global limit that blocks one venue NAT.

  Parallelization: Can parallel: YES | Wave 5 | Blocks: [24, 26] | Blocked by: [15, 16, 19]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/java/com/naraesigning/auth/LoginAttemptStore.java:1-184` - bounded in-process limiter approach.
  - API/Type: `main:backend/src/main/java/com/naraesigning/session/SignerSessionContract.java:8-66` and `CsrfContractFilter.java:45-48` - signer session and public CSRF materialization.
  - Test:     `main:backend/src/test/java/com/naraesigning/security/BrowserSessionContractTest.java:1-95` - cookie/path contract.
  - External: `docs/plan/PRODUCT_PLAN.md:114-141` and `docs/plan/IMPLEMENTATION_PLAN.md:275-290,344-353` - public disclosure/rate rules.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd backend && ./gradlew test --tests '*PublicIdentify*'` exits 0 without Docker.
  - [ ] Tests prove setup/open/closed/invalid minimal states, exact raw match, already-submitted and unplaced denial, 30-minute signer session, link/revision/aspect binding, exact pair/IP rates, 10,000-entry oldest-idle eviction, forged-forwarded-IP handling, deterministic 25-person NAT burst pass, bounded `Retry-After`, and no enumeration/log leakage.
  - [ ] Public/token responses use `Cache-Control: no-store, private` and `Referrer-Policy: no-referrer` on success and every error; static hashed assets are unaffected.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Assigned signer identifies exactly
    Tool:     bash
    Steps:    Run MockMvc GET link state, materialize CSRF, then POST exact synthetic organization/job/name from one trusted NAT IP.
    Expected: Signer cookie is path-scoped/Secure/HttpOnly/SameSite; session has IDs/versions/aspect only; no identity echo.
    Evidence: <attemptDir>/task-21-public-identify.log

  Scenario: Near match and venue abuse disclose nothing
    Tool:     bash
    Steps:    Try whitespace/case variants, submitted/unplaced identities, old token, 25 allowed distinct requests, then excess abusive requests.
    Expected: Generic states are indistinguishable by body shape; normal burst passes; abuse receives bounded 429 without roster lockout.
    Evidence: <attemptDir>/task-21-public-identify-error.log
  ```

  Commit: YES | Message: `feat(signing): add private link identification` | Files: [`backend/src/main/java/com/naraesigning/signer/identify/**`, `backend/src/test/java/com/naraesigning/signer/identify/**`]

- [ ] 22. Build administrator board list, creation, and roster workflow

  What to do: Consume Task 14's shell/token foundation without editing its owned files. Replace in-file route placeholders with typed Zod-parsed board list/create/editor routes, Korean status/error/reload states, direct roster editing, paste/file preview and server error markers. Task 22 exclusively edits `AppRouter.tsx`, creates `AdminBoardRoutes.tsx`, and creates skeletal `FullViewRoute.tsx` and `PublicSignerRoute.tsx`; after integration those two route modules transfer exclusively to Tasks 25 and 26. Apply existing query/confirmation/toast patterns.
  Must NOT do: No client-authoritative import acceptance, raw crypto metadata, deleted boards, optimistic lifecycle truth, new component library, or edit to later task-owned route modules after handoff.

  Parallelization: Can parallel: YES | Wave 6 | Blocks: [23, 25, 26, 27] | Blocked by: [14, 18, 20]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/api/client.ts:24-56`, `app/queryClient.ts:1-8`, `components/ConfirmDialog.tsx:1-28`, `components/Toast.tsx:1-38` - API/query/interaction primitives.
  - API/Type: `main:frontend/src/routes/AppRouter.tsx:31-75` - route markers and current shells.
  - Test:     `main:frontend/e2e/admin-auth.spec.ts:1-221` - browser auth pattern.
  - External: `DESIGN.md:8-24,59-119,121-202,258-428` - required tokens and visual rules.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd frontend && npm run test && npm run typecheck && npm run lint` exits 0.
  - [ ] Zod schemas reject malformed API data; tests cover create/list/navigation, empty/error/reload, exact roster values, rejected import preserving server snapshot, safe row markers, and exact route-module handoff. Ownership diff contains no Task 14 shell/style file.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Admin creates board and imports roster
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/admin-board-list.spec.ts --project=chromium`. The existing Playwright `webServer` config is the sole Vite owner; the spec uses deterministic route fixtures, visits `/boards`, creates a Korean-titled board, pastes 25 rows, uploads CSV, and opens the editor. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Keyboard-operable Korean UI uses declared tokens; server snapshot renders exactly; no console/page error.
    Evidence: <attemptDir>/task-22-admin-board-ui.png

  Scenario: Rejected import preserves visible snapshot
    Tool:     playwright(real Chrome)
    Steps:    Return deterministic duplicate/name errors and malformed API JSON; activate reload.
    Expected: Error rows/fields are visible without raw backend prose; previous accepted roster remains; malformed response reaches generic error state.
    Evidence: <attemptDir>/task-22-admin-board-ui-error.png
  ```

  Commit: YES | Message: `feat(frontend): add board and roster workflow` | Files: [`frontend/src/features/boards/list/**`, `frontend/src/features/boards/roster/**`, `frontend/src/routes/AdminBoardRoutes.tsx`, `frontend/src/test/AdminBoardRoutes.test.tsx`, `frontend/src/routes/FullViewRoute.tsx`, `frontend/src/routes/PublicSignerRoute.tsx`, `frontend/src/routes/AppRouter.tsx`, `frontend/src/test/AppRouter.test.tsx`, `frontend/e2e/admin-board-list.spec.ts`]

- [ ] 23. Build desktop canvas editor, background, share, and lifecycle controls

  What to do: Consume Task 14's foundation and Task 22's route handoff without editing their shell/route files. Add BoardToolbar, CanvasViewport, SlotOverlay, unplaced roster panel, drag/resize with normalized coordinates, collision preview plus server rejection recovery, debounced serialized autosave, background upload/ratio-adoption confirmation, share URL copy/QR, open/close/reopen, status filters, and action extension points for later realtime/PNG/delete components. Task 23 receives the post-Task-14 `frontend/package.json`/lockfile handoff and alone may add one pinned maintained QR dependency when native capability is absent.
  Must NOT do: No final PNG/delete implementation, concurrent mutation replay, client-only collision authority, token persistence, mobile admin layout promise, or magic visual values outside `DESIGN.md`.

  Parallelization: Can parallel: YES | Wave 7 | Blocks: [25, 27, 28] | Blocked by: [14, 16, 17, 19, 20, 22]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/api/client.ts:24-56` and `queryClient.ts:3-7` - no mutation retries and refetch model.
  - API/Type: `docs/plan/IMPLEMENTATION_PLAN.md:87-174` - editor components, normalized canvas, autosave, roster UI.
  - Test:     `main:frontend/e2e/route-shell.spec.ts:47-115` - browser evidence pattern.
  - External: `docs/plan/PRODUCT_PLAN.md:47-112` and `DESIGN.md:74-218` - confirmations, layout, and component tokens.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd frontend && npm run test && npm run typecheck && npm run lint && npx playwright test --project=chromium --grep '@admin-editor'` exits 0.
  - [ ] Tests prove normalized coordinate conversion, serialized autosave ordering, failure rollback/refetch, touching-edge placement, background confirmation, share reissue warning, QR matching current URL, and open prerequisites from server.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Admin places roster and opens board
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/admin-editor.spec.ts --project=chromium`. The existing Playwright `webServer` config is the sole Vite owner; at 1280x800 the spec drags two unplaced people, resizes to touching edges, uploads a synthetic image, copy/scan-decoder-checks the QR, then opens/closes/reopens. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Canvas and status remain server-derived; autosave settles; focus/keyboard controls and confirmations work; screenshot matches design contract.
    Evidence: <attemptDir>/task-23-admin-canvas-ui.png

  Scenario: Collision, stale save, and ratio change recover
    Tool:     playwright(real Chrome)
    Steps:    Force overlap 409, delay first save behind second, reject one upload, and cancel then confirm ratio adoption.
    Expected: No lost update; failed state refetches; old background/layout stays after rejection/cancel; confirmed adoption rescales once.
    Evidence: <attemptDir>/task-23-admin-canvas-ui-error.png
  ```

  Commit: YES | Message: `feat(frontend): add board canvas editor` | Files: [`frontend/src/features/boards/editor/**`, `frontend/src/features/boards/background/**`, `frontend/src/features/boards/share/**`, `frontend/package.json`, `frontend/package-lock.json`, `frontend/e2e/admin-editor.spec.ts`]

- [ ] 24. Implement first-wins encrypted signature submission

  What to do: Parse the exact version-1 signature wire contract, enforce all bounds before allocation, and validate both signer-session idle/current state and absolute expiry against locked board/slot state. Under board-then-slot locks, enforce the cross-task submission invariant and fail closed on any split state. In one transaction encrypt strokes with slot-bound AAD, write ciphertext metadata/submission time/roster completion, and publish only after commit. Add a typed frontend submit adapter that never auto-retries mutations and retains strokes after transport failure.
  Must NOT do: No raster signature storage, plaintext vector/log, second submission, stale link/revision/aspect acceptance, or retry loop hidden in client/query settings.

  Parallelization: Can parallel: YES | Wave 6 | Blocks: [25, 26, 27, 28] | Blocked by: [18, 19, 21]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/api/client.ts:31-49` and `queryClient.ts:5-6` - CSRF and no mutation replay.
  - API/Type: `main:backend/src/main/java/com/naraesigning/session/SignerSessionContract.java:35-84`, `V1__core_schema.sql:42-65` - stale matrix and encrypted stroke fields.
  - Test:     `main:backend/src/test/java/com/naraesigning/session/InvalidationSessionContractTest.java:1-90` - revision-state assertions.
  - External: `docs/plan/PRODUCT_PLAN.md:126-141` and `docs/plan/IMPLEMENTATION_PLAN.md:275-290` - submit/retry/privacy behavior.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; targeted backend `*SignatureSubmit*` and frontend signing API tests exit 0 without Docker/network.
  - [ ] Golden-vector tests prove exact integer JSON decoding and the same black round-cap/round-join pixel output as Task 18; input caps reject before crypto/database allocation.
  - [ ] Tests prove the atomic two-table submission invariant, ciphertext/AAD, first-wins conditional update, split-state fail-closed behavior, absolute/idle expiry and every stale/closed/deleting state, after-commit event, no plaintext log, no auto-retry, and retained client strokes after network failure.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: First current submission wins
    Tool:     bash
    Steps:    Run two barrier-controlled service calls with identical current signer session and different synthetic strokes.
    Expected: Exactly one 2xx-equivalent result, one stable already-submitted conflict, one ciphertext, one event.
    Evidence: <attemptDir>/task-24-signature-submit.log

  Scenario: Stale and failed network submissions preserve safety
    Tool:     bash
    Steps:    Exercise old link/revision/aspect, CLOSED/DELETING, malformed payload, and frontend transport rejection.
    Expected: Zero write/event on server; generic UI mapping; client points remain available for explicit retry only.
    Evidence: <attemptDir>/task-24-signature-submit-error.log
  ```

  Commit: YES | Message: `feat(signing): enforce first-wins encrypted submission` | Files: [`backend/src/main/java/com/naraesigning/signature/**`, `backend/src/test/java/com/naraesigning/signature/**`, `frontend/src/features/signing/api/**`]

- [ ] 25. Add manager-only SSE and snapshot-driven full view

  What to do: Implement owner-authorized `SseEmitter` registry whose entry checks the admin absolute-session contract, post-commit board event IDs/types without roster/stroke data, heartbeat, disconnect cleanup, and no replay store. Consume Task 22's `FullViewRoute.tsx` handoff. Build full-view listeners and the post-Task-23 `RealtimeBoardBridge.tsx` editor bridge; reconnect with bounded exponential backoff and always refetch an authoritative snapshot. Render background, white/transparent slots, and submitted signatures via the deterministic signature renderer without identities or controls.
  Must NOT do: No public SSE, Redis, WebSocket, event payload snapshot, in-progress stroke, or client state patched solely from event order.

  Parallelization: Can parallel: YES | Wave 8 | Blocks: [28, 30] | Blocked by: [14, 20, 22, 23, 24]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:infra/nginx/default.conf:24-31` - existing no-buffer SSE proxy route.
  - API/Type: `docs/plan/IMPLEMENTATION_PLAN.md:308-315` - event and snapshot contract.
  - Test:     `main:frontend/e2e/route-shell.spec.ts:38-93` - route browser pattern.
  - External: `https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html` - `SseEmitter`, heartbeat, disconnect behavior.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; targeted backend realtime tests plus frontend full-view tests and `npm run typecheck` exit 0.
  - [ ] Tests prove absolute admin-session expiry before SSE registration/refetch, owner isolation, no-store/private and no-referrer headers, heartbeat/cleanup, event minimality, post-commit timing, reconnect backoff, snapshot refetch after missed/duplicate/out-of-order events, editor-bridge refetch, and identity-free deterministic render.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Submitted signature appears from fresh snapshot
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/full-view.spec.ts --project=chromium`. The existing Playwright `webServer` config is the sole Vite owner; the spec opens `/boards/<id>/full`, triggers a sanitized SSE event fixture after the backend snapshot changes, and inspects refetch/render. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Signature appears at normalized slot; no identity/editor controls; no page/console error.
    Evidence: <attemptDir>/task-25-realtime-fullview.png

  Scenario: Disconnect and event loss recover
    Tool:     playwright(real Chrome)
    Steps:    Drop stream, skip one event ID, send duplicate/out-of-order events, restore connection.
    Expected: Bounded reconnect; one authoritative refetch settles current board; no duplicate signature or stale identity.
    Evidence: <attemptDir>/task-25-realtime-fullview-error.png
  ```

  Commit: YES | Message: `feat(realtime): add owner SSE and full view` | Files: [`backend/src/main/java/com/naraesigning/realtime/**`, `backend/src/test/java/com/naraesigning/realtime/**`, `frontend/src/features/boards/fullview/**`, `frontend/src/routes/FullViewRoute.tsx`, `frontend/src/features/boards/editor/RealtimeBoardBridge.tsx`, `frontend/e2e/full-view.spec.ts`]

- [ ] 26. Complete public signer states and resilient tablet flow

  What to do: Consume Task 22's `PublicSignerRoute.tsx` handoff and Task 14's shell without editing other route/shell files. Build Korean waiting/open/closed/invalid/identified/drawing/submitting/complete states. Use exact optional fields, retention notice, Task 18 pad, Task 24 adapter, explicit retry preserving strokes, submitted re-entry, referrer/privacy controls, and the exact `min(width,height) >= 600 CSS px` support gate before any token request.
  Must NOT do: No participant list/slot position, auto retry, preview/second confirmation, browser token storage, legal copy, or smartphone bypass.

  Parallelization: Can parallel: YES | Wave 7 | Blocks: [30] | Blocked by: [14, 18, 21, 22, 24]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:frontend/src/components/AsyncViews.tsx:4-47`, `UnsupportedDeviceView.tsx:1-8`, `api/errors.ts:3-78` - stable states/copy.
  - API/Type: `main:frontend/src/routes/useTabletSupport.ts:1-14` - phone gate.
  - Test:     `main:frontend/e2e/route-shell.spec.ts:96-115` - no-request phone proof.
  - External: `docs/plan/PRODUCT_PLAN.md:114-141` and `DESIGN.md:8-218` - signer behavior and visual contract.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; `cd frontend && npm run test && npm run typecheck && npx playwright test --project=chromium --project=webkit --grep '@public-signer'` exits 0.
  - [ ] Tests cover every state, exact fields, retention notice before submit, absolute/idle signer-session expiry, failure retention/retry, submitted re-entry, 599px rejection and 600px acceptance in both orientations, phone zero requests/canvas, no storage writes, no disclosure, and both tablet orientations.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Tablet signer completes after one retry
    Tool:     playwright(real Chrome)
    Steps:    Acquire `playwright-4173.lock` with a shell trap that always releases it; assert `lsof -nP -iTCP:4173 -sTCP:LISTEN` finds no listener; run `cd frontend && npx playwright test e2e/public-signer.spec.ts --project=chromium --project=webkit`. The existing Playwright `webServer` config is the sole Vite owner; the spec uses 768x1024 and 1024x768 viewports, identifies an exact synthetic user, draws with pointer events, fails the first transport, retries explicitly, and reloads. After Playwright exits, assert port 4173 has no listener, then release the lock through the trap. Do not manage Vite outside Playwright.
    Expected: Strokes survive failure; one success leads to completion; reload shows already-submitted without other participant data.
    Evidence: <attemptDir>/task-26-public-signer.png

  Scenario: Unsupported and stale paths leak nothing
    Tool:     playwright(real Chrome)
    Steps:    Visit on 390x844 phone, then tablet with invalid/old/closed links and stale signer session; inspect storage and network log.
    Expected: Phone sends zero token requests; generic Korean states only; local/session/IndexedDB/Cache storage contain no token or identity.
    Evidence: <attemptDir>/task-26-public-signer-error.png
  ```

  Commit: YES | Message: `feat(frontend): complete resilient signer flow` | Files: [`frontend/src/features/signing/flow/**`, `frontend/src/routes/PublicSignerRoute.tsx`, `frontend/src/routes/useTabletSupport.ts`, `frontend/e2e/public-signer.spec.ts`]

- [ ] 27. Render deterministic private final PNG

  What to do: For a current absolute-session owner and `CLOSED` only, read one locked/snapshotted current board, decrypt background/strokes, render the exact shared signature contract with Java2D at background resolution or 1920x1080, include white slot fills when configured, exclude identities/UI, and stream PNG without storing it. Guard with one JVM semaphore; competing request returns 503 plus `Retry-After`. Set no-store/private and no-referrer headers. Consume Task 23's editor extension handoff for download/retry action.
  Must NOT do: No cached/stored result, JPG, individual signature export, unbounded concurrent render, name/title/selection/QR overlay, or automatic client retry.

  Parallelization: Can parallel: YES | Wave 8 | Blocks: [28, 30] | Blocked by: [17, 19, 22, 23, 24]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/resources/db/migration/V1__core_schema.sql:42-80` - geometry/signature/background sources.
  - API/Type: `docs/plan/IMPLEMENTATION_PLAN.md:324-342` - Java2D, privacy, dimensions, semaphore.
  - Test:     `main:backend/src/test/java/com/naraesigning/crypto/VersionedCryptoServiceTest.java:1-216` - deterministic crypto fixture style.
  - External: `docs/plan/PRODUCT_PLAN.md:143-158` - visible PNG contract.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; targeted `*FinalPng*` backend and frontend tests exit 0 without MinIO/Docker.
  - [ ] Golden pixel tests prove the Task 18/24 integer wire vectors render with exact black round-cap/round-join width, blank/background dimensions, normalized placement, transparent/white slot treatment, identity/UI exclusion, current-after-reclose snapshot, 503 concurrency, cache/referrer headers, absolute-session expiry, and zero retained result object.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Closed board downloads exact current PNG
    Tool:     bash
    Steps:    Render deterministic 1920x1080 and small-background fixtures; decode output and assert selected pixels/dimensions/chunks.
    Expected: Expected signature and white-slot pixels only; no text metadata; valid PNG; no output object persisted.
    Evidence: <attemptDir>/task-27-final-png.png

  Scenario: Unauthorized/open/concurrent render fails safely
    Tool:     bash
    Steps:    Run owner-B, OPEN board, corrupt ciphertext, and held-semaphore second request tests.
    Expected: Generic 403/409/500 mapping as specified; second current render gets 503 `Retry-After`; first result/state remains intact.
    Evidence: <attemptDir>/task-27-final-png-error.log
  ```

  Commit: YES | Message: `feat(render): add private closed-board PNG` | Files: [`backend/src/main/java/com/naraesigning/render/**`, `backend/src/test/java/com/naraesigning/render/**`, `frontend/src/features/boards/editor/FinalPngAction.tsx`]

- [ ] 28. Implement access-first permanent deletion and durable object cleanup

  What to do: Add confirmed current-owner delete UI/API and forward-only `V4__deletion_job_lease.sql`. Phase A locks the board, sets `DELETING`, invalidates link/session validation, and inserts encrypted per-object cleanup jobs in one transaction; access disappears at commit while the relational graph remains available to the cleanup lifecycle. A scheduled in-process poller claims jobs with database leases (`lease_token`, `lease_expires_at`) using skip-locked semantics, renews or safely expires leases, deletes idempotently, records bounded attempts/backoff and redacted error codes, and retries indefinitely with capped one-hour delay rather than abandoning. Phase B, only after every object job succeeds, transactionally removes completed jobs and the relational board graph. Consume Task 25's editor bridge handoff for delete UI/event refetch.
  Must NOT do: No soft-delete/restore UI, queue/worker service, public cleanup detail, plaintext object key/error, database rollback after access has been blocked, or silent abandonment after MinIO failure.

  Parallelization: Can parallel: NO | Wave 9 | Blocks: [30] | Blocked by: [15, 17, 19, 20, 23, 24, 25, 27]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:backend/src/main/resources/db/migration/V1__core_schema.sql:82-97` - durable deletion job already exists.
  - API/Type: `main:backend/src/main/java/com/naraesigning/session/SignerSessionContract.java:35-48` - `DELETING` invalidation.
  - Test:     `main:backend/src/test/java/com/naraesigning/session/InvalidationSessionContractTest.java:1-90` - access invalidation checks.
  - External: `docs/plan/PRODUCT_PLAN.md:160-165` and `docs/plan/IMPLEMENTATION_PLAN.md:316-322` - permanent deletion order.

  Acceptance criteria (agent-executable only):
  - [ ] RED log precedes code; targeted `*BoardDeletion*` backend/frontend tests exit 0 with an object-store fake.
  - [ ] Migration test proves V4 upgrades from V3, lease columns/indexes/constraints exist, and current V3 data survives; Task 28 is the only migration owner.
  - [ ] Tests prove confirmation, absolute-session/owner scope, Phase-A atomic DELETING+encrypted jobs, immediate list/link/SSE/final-PNG denial, two-poller exclusive claim, lease expiry/reclaim/renewal, idempotent object-not-found success, restart-resume, indefinite capped-backoff retry without abandonment, Phase-B graph/job removal only after all objects, and no plaintext/error leakage.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Board and objects are permanently deleted
    Tool:     bash
    Steps:    Run service/API tests on a board with background and signature, then execute cleanup fake successfully.
    Expected: Board graph absent, token invalid, object deleted once, job completed, list excludes board.
    Evidence: <attemptDir>/task-28-board-deletion.log

  Scenario: Object store outage preserves blocked access and retries
    Tool:     bash
    Steps:    Fail two fake-object deletes, crash one poller while leased, start a second before and after lease expiry, simulate process restart, then succeed; query all former routes after each step.
    Expected: Access stays unavailable; no double claim before expiry; persisted schedule advances without terminal abandonment; final Phase B removes graph/jobs without plaintext key.
    Evidence: <attemptDir>/task-28-board-deletion-error.log
  ```

  Commit: YES | Message: `feat(board): add durable permanent deletion` | Files: [`backend/src/main/java/com/naraesigning/deletion/**`, `backend/src/test/java/com/naraesigning/deletion/**`, `backend/src/main/resources/db/migration/V4__deletion_job_lease.sql`, `frontend/src/features/boards/editor/DeleteBoardAction.tsx`]

- [ ] 29. Build local-only release delivery and deployment preflight

  What to do: Add manual `workflow_dispatch` release workflow that accepts an existing `vX.Y.Z`, checks out its peeled commit, builds frontend/backend images, attaches revision labels, targets private GHCR, and calls Portainer only after both image builds/pushes succeed. Own and update `docs/REVERSE_PROXY_SETUP_GUIDE.md` with the exact HTTPS redirect, forwarded-header trust, Secure cookie, NPM websocket/SSE buffering, timeout, and validation contract. Add local static/fixture verification, rollback selection checks, redacted preflight, NPM/SSE/cookie checklist, and physical-device checklist. Keep execution dry/local during this plan unless push/deployment is separately authorized.
  Must NOT do: No automatic push/deploy, mutable source tag, force-push, registry/webhook call during acceptance, secret in logs/artifacts, public backend/Postgres/MinIO, down migration, backup feature, or mutation of manual stack.

  Parallelization: Can parallel: YES | Wave 2 | Blocks: [30] | Blocked by: [13]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:.github/workflows/ci.yml:1-72` and `main:scripts/verify-workflows.sh:1-31` - current CI and workflow lint boundary.
  - API/Type: `main:infra/compose/compose.yml:1-122`, `main:infra/nginx/default.conf:1-84` - private topology and proxy routes.
  - Test:     `main:scripts/scan-tracked-secrets.sh:1-19` - tracked-secret gate.
  - External: `https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images` and `docs/REVERSE_PROXY_SETUP_GUIDE.md:203-245` - GHCR and external validation.

  Acceptance criteria (agent-executable only):
  - [ ] RED fixture proves workflow verifier catches a mutable/unpeeled tag or early webhook; release script fixture suite, workflow lint fixture, Compose config-only validation, and secret scan exit 0 without Docker network creation.
  - [ ] Workflow has minimum permissions, commit-SHA-pinned third-party actions, two immutable images bound to the same peeled tag SHA, no webhook before both digests, and explicit no-push dry-run path used by this task.
  - [ ] `git status`/remote refs/registry/Portainer/manual-stack stable snapshot prove zero external mutation.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Existing tag plans two immutable images then webhook
    Tool:     bash
    Steps:    Run release scripts against local fake Git/registry/webhook adapters with tag `v0.1.0`; inspect ordered call ledger and revision labels.
    Expected: Frontend/backend use same peeled SHA; webhook is last; dry run makes zero external call.
    Evidence: <attemptDir>/task-29-release-delivery.log

  Scenario: Partial image, bad tag, secret, or failed build blocks delivery
    Tool:     bash
    Steps:    Run fixtures for non-`vX.Y.Z`, moved/missing tag, one failed image, digest/revision mismatch, and secret-like output.
    Expected: Nonzero before webhook/alias mutation; redacted logs; no Git remote or manual-stack change.
    Evidence: <attemptDir>/task-29-release-delivery-error.log
  ```

  Commit: YES | Message: `ci(release): add manual immutable delivery gate` | Files: [`.github/workflows/release.yml`, `scripts/release/**`, `infra/portainer/**`, `docs/qa/MVP_TABLET_RELEASE_CHECKLIST.md`, `docs/REVERSE_PROXY_SETUP_GUIDE.md`, `scripts/test-release-delivery.sh`]

- [ ] 30. Run integrated database, object-store, browser, race, render, and topology verification

  What to do: Add only missing integration/E2E harness and run the entire clean candidate. Before any Docker/Testcontainers/Compose action, obtain separate explicit execution-time authority from the user; plan wording, coordinator choice, or earlier authority is not authority. Capture the exact manual-stack stable snapshot, stop its containers without removing them/networks/volumes/binds, run Testcontainers integration to completion and tear it down, then run one uniquely named isolated Compose project to completion and tear it down. Never overlap Testcontainers, Compose, or the retained stack. Always restart original containers and prove stable restoration plus `127.0.0.1:18083` health. If authority is absent, record `BLOCKED: manual stack suspension authorization unavailable` and do not start Docker/network work. Task 30 evidence is the sole Docker/manual-stack authority for later gates.
  Must NOT do: No product fix hidden in test branch. A failure reopens its owning feature task/branch, which amends its one unpublished task commit under the commit strategy, reruns evidence, and re-integrates before Task 30 restarts. No manual-stack removal/recreation, bind modification, volume deletion, port 18083 reuse, push, deploy, or real PII.

  Parallelization: Can parallel: NO | Wave 10 | Blocks: [31, F1, F2, F3, F4] | Blocked by: [25, 26, 27, 28, 29]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `main:.github/workflows/ci.yml:21-67` - full clean command matrix.
  - API/Type: `main:backend/src/integrationTest/java/com/naraesigning/FlywaySchemaIT.java:1` and `main:frontend/playwright.config.ts:1-20` - integration/browser harness.
  - Test:     `docs/plan/IMPLEMENTATION_PLAN.md:459-479` - required automated/manual scenarios.
  - External: `main:infra/compose/README.md:1-9` - capacity/private topology contract.

  Acceptance criteria (agent-executable only):
  - [ ] Authorized run records before/after stable manual-stack equality and restored HTTP 200 at `http://127.0.0.1:18083/health`; trap cleanup removes only the unique test project.
  - [ ] `cd frontend && npm ci && npm run lint && npm run typecheck && npm run test && npm run build && npx playwright test --project=chromium --project=webkit`; `cd backend && ./gradlew clean check integrationTest`; Compose config/resource gate, workflow gates, release fixtures, and tracked-secret scan all exit 0.
  - [ ] PostgreSQL barriers prove conflicting layout and simultaneous submit first-wins; real MinIO proves ciphertext storage/background roundtrip/deletion retry; rendered PNG pixel/dimension checks pass; E2E covers complete synthetic event.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Complete synthetic event passes on isolated candidate
    Tool:     playwright(real Chrome)
    Steps:    After authorized manual-stack suspension, run `./scripts/verify-mvp.sh` with unique project/ports; create admin/board/roster/background/slots, open, identify, sign, observe SSE/full view, close, download PNG, delete; restore stack in trap.
    Expected: Every command exits 0; event outcomes match product; evidence contains no PII/token/cookie/key; original port 18083 stack returns healthy with identical stable fields.
    Evidence: <attemptDir>/task-30-integrated-mvp.zip

  Scenario: Race and cleanup failures remain safe
    Tool:     bash
    Steps:    Run concurrent slot/submit/render tests, old-link/reissue, object-delete outage/restart, and forced test-stack failure while trap executes.
    Expected: One winner per race; stable conflicts; no plaintext; isolated stack removed; original manual stack restored even on failure.
    Evidence: <attemptDir>/task-30-integrated-mvp-error.log
  ```

  Commit: YES | Message: `test(mvp): verify integrated signing event` | Files: [`backend/src/integrationTest/java/com/naraesigning/mvp/**`, `frontend/e2e/mvp-flow.spec.ts`, `scripts/verify-mvp.sh`, `scripts/fixtures/**`]

- [ ] 31. Execute deployed physical-tablet acceptance

  What to do: Only after Task 30 passes and separate explicit push/tag/deploy authority produces verified immutable image digests, drive deployed `https://signing.lapis0875.com` through an approved device-control bridge on iPadOS 16+ Safari and Android 12+ Chrome, portrait and landscape. Cover pen/touch, failed-send retry, old link, close/delete, Secure cookie, SSE, and PNG. Record synthetic/redacted evidence. The plan and prior deployment history are not authority to push, tag, deploy, or operate external devices.
  Must NOT do: No deployment/push/tag under this task, desktop emulation as physical proof, real participant data, manual unsupported claims, or source/doc commit. If deployment authority or either device bridge is absent, record exact blocker and leave production-readiness false.

  Parallelization: Can parallel: NO | Wave 11 | Blocks: [F1, F2, F3, F4] | Blocked by: [30]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `docs/qa/MVP_TABLET_RELEASE_CHECKLIST.md` - Task 29 checklist.
  - API/Type: `docs/plan/PRODUCT_PLAN.md:7-17,114-158` - supported devices and signer/result contract.
  - Test:     `docs/plan/IMPLEMENTATION_PLAN.md:468-479` - physical QA matrix.
  - External: `docs/REVERSE_PROXY_SETUP_GUIDE.md:212-245` - deployed HTTPS/SSE/cookie checks.

  Acceptance criteria (agent-executable only):
  - [ ] Device bridge records all four platform/orientation sets, happy and failure flows, tag/commit/image digests, and redacted network/cookie assertions; otherwise task status is `BLOCKED` with exact absent authority/device.
  - [ ] No source/ref/registry/deployment/manual-stack mutation occurs from this task itself.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Both physical tablet families complete event flow
    Tool:     computer-use
    Steps:    On enrolled iPad Safari and Android Chrome in portrait/landscape, open QR URL, identify synthetic users, draw by pen/touch, force one failed send, retry, and observe manager SSE/full view/PNG.
    Expected: Four device/orientation passes; one signature each; retry retains strokes; manager sees submitted result only.
    Evidence: <attemptDir>/task-31-physical-tablets.zip

  Scenario: Old link, close, delete, and HTTP downgrade fail safely
    Tool:     computer-use
    Steps:    Reissue link during signer session, close and delete boards in later sessions, try HTTP origin, and inspect browser network/cookie metadata through device bridge.
    Expected: Generic unavailable states, no participant leak, Secure/HttpOnly/SameSite cookies, HTTPS redirect, no stale submission.
    Evidence: <attemptDir>/task-31-physical-tablets-error.zip
  ```

  Commit: NO | Message: `N/A - external device evidence only` | Files: [`<attemptDir>/task-31-physical-tablets*.zip`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - verify initial and post-Task-13 baselines, committed immutable plan blob/SHA, Recovery Policy-A semantic bindings and limitation, all 19 task rows, dependency order, exclusive ownership/handoffs, RED/GREEN evidence, exactly one final task commit per Tasks 13-30, real commit SHAs/subjects, no-ff merges, Task 31 status, and no historical artifact or plan-snapshot change. Evidence: `<attemptDir>/F1-successor-01.md`.
- [ ] F2. Code quality review - using Task 30's passed authorized evidence, inspect clean frontend/backend/type/semantic-lint/test/build/integration results, JDBC transactions/locks/leases, crypto contexts, stream closure, render memory, async cleanup, TS Zod boundaries, accessibility, dead code, dependency pins, and absence of production design-tool code. Do not rerun Docker/Testcontainers/Compose or claim stack preservation independently. Evidence: `<attemptDir>/F2-successor-01.md`.
- [ ] F3. Real manual QA - acquire the shared browser lock and independently replay every non-Docker browser scenario at 375x812, 768x1024, 1024x768, and 1280x800 in Chromium/WebKit; validate operational-token compliance, excluded marketing/OAuth absence, and interaction states; then require Task 31 physical evidence for approval. Missing physical/deployment/device authority is `BLOCKED`, never approved. Evidence: `<attemptDir>/F3-successor-01.md`.
- [ ] F4. Scope fidelity - inspect `f4eae935874b7b84a980c19614cdcb7baffcea83..main`, Compose/public ports, tracked secrets, browser storage, logs, MinIO policy, feature routes, recovery/worktree hashes, and Git remotes. Use only Task 30's passed before/after record for Docker/manual-stack equality; reject any Must-NOT-Have, push/deploy, historical mutation, plan-byte change, or port-18083 drift. Evidence: `<attemptDir>/F4-successor-01.md`.

## Commit strategy
- Task 13 starts from exact declared `main`; every later task branch starts from the integrated dependency tip in its own worktree. Never implement directly on `main`.
- One source task equals exactly one final non-merge atomic implementation-unit commit containing behavior and direct tests. Do not commit until the task's RED proof exists and its GREEN/narrow/manual gates pass. Use the exact Conventional Commit subject listed in the task; add a body with RED/GREEN commands and footer `Plan: .omo/plans/narae-signing-web-mvp-successor-01.md`.
- Parallel tasks own disjoint paths. Shared route/editor handoffs occur only after predecessor no-ff integration; no concurrent task edits the same file.
- Before each commit: inspect complete diff, stage only owned paths, run narrow acceptance, verify staged diff. Before each integration: make a disposable merge proof, rerun affected gates, then `git merge --no-ff <branch>` on a clean coordinator worktree.
- Do not push. Do not create/push release tags, publish images, call Portainer, or deploy until the user explicitly requests that external mutation after reviewing local evidence.
- Before a private unpublished task branch is reviewed/integrated, a discovered defect is fixed there, then folded into its single task commit by amend/squash; rerun all task gates and bind evidence to the resulting final SHA. After review or integration, never amend/rebase that commit; any correction becomes a separately planned successor task/branch/commit, not a second commit attributed to the old task.
- Final source history contains exactly one task commit for each committing Task 13-30 plus no-ff merge commits. Task 31 and F1-F4 are evidence-only and create no commit.

## Success criteria
- All Must-Have behavior ships; every task has RED/GREEN, automated, adversarial, real-surface, cleanup, evidence, and exact commit proof.
- Historical Todos 1-12 and Recovery-01/02/03 remain byte-identical; Policy-A limitation remains disclosed; `main` ancestry starts at the exact declared candidate.
- Feature branches integrate without ownership collisions; commit history is atomic and reviewable; no push/deploy occurred without explicit authority.
- Full clean integration passes and retained manual stack at `127.0.0.1:18083` is unchanged/restored.
- F1-F4 approve. If Task 30 Docker authorization or Task 31 deployed physical-device evidence is unavailable, status stays truthfully blocked and production-readiness is not declared.
