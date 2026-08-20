---
slug: narae-signing-web-mvp
status: review-complete
intent: clear
review_required: true
pending-action: none
plan-path: .omo/plans/narae-signing-web-mvp.md
plan-sha256: 3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca
approach: Build the greenfield MVP as dependency-ordered vertical slices: operable foundation, security and identity, board authoring, signer flow and live state, final artifact and deployment, then automated and real-tablet verification.
---

# Draft: narae-signing-web-mvp

## Components (topology ledger)

| id | outcome (one line) | status | evidence path |
| --- | --- | --- | --- |
| frontend | Korean React admin and tablet signer experiences, with no smartphone support boundary. | active | `docs/plan/IMPLEMENTATION_PLAN.md:23-32, 85-149` |
| backend | Single Spring Boot process owns REST, state transitions, sessions, encryption, SSE, PNG rendering, and deletion recovery. | active | `docs/plan/IMPLEMENTATION_PLAN.md:23-32, 176-353` |
| database | PostgreSQL schema holds owner-scoped boards, encrypted roster/signature data, session rows, and durable deletion jobs. | active | `docs/plan/IMPLEMENTATION_PLAN.md:193-223` |
| object-storage | Private MinIO holds only application-encrypted background assets. | active | `docs/plan/IMPLEMENTATION_PLAN.md:212-223, 324-343` |
| deployment | Compose/Portainer/NPM exposes only the frontend origin at `https://signing.lapis0875.com`. | active | `docs/plan/IMPLEMENTATION_PLAN.md:41-84, 355-408`; `docs/REVERSE_PROXY_SETUP_GUIDE.md:17-246` |
| quality | Unit, integration, browser, and real-tablet verification cover the accepted event flow. | active | `docs/plan/IMPLEMENTATION_PLAN.md:451-479` |

## Planned execution spine

1. Create the repository skeleton, deterministic toolchain contract, Compose topology, first Flyway migration, health checks, and CI verification.
2. Implement admin authentication, JDBC sessions, CSRF, absolute expiry, login lockout, one-time operator commands, and application-layer crypto primitives.
3. Implement owner-scoped board, roster, link, slot, and lifecycle transactions plus the desktop authoring UI.
4. Implement image ingestion, public signer identity/session/submission, native tablet canvas, manager-only SSE, and full view.
5. Implement deterministic final PNG rendering, transactional access revocation with durable object purge, release promotion, and reverse-proxy deployment artifacts.
6. Complete focused automated tests and execute the accepted external/browser/tablet manual-QA matrix.

## Open assumptions (announced defaults)

| assumption | adopted default | rationale | reversible? |
| --- | --- | --- | --- |
| Stale signer sessions | Add `slot_revision` to `signature_slot`; capture it at identify and require it at submit. Increment it on reset, slot deletion, roster reassignment, and unsubmitted identity edit. | The approved session contents otherwise cannot prove that a previously identified signer is still entitled to submit. | Yes, migration-backed. |
| Two session scopes | Use one Spring Session JDBC repository with a path-aware HTTP session-ID resolver: `ADMIN_SESSION` with `Path=/api/v1`, and `SIGNER_SESSION` with `Path=/api/v1/public`. Resolve only the appropriate named cookie for each route family. | Preserves the approved distinct cookie names/paths without a second session store or browser-accessible credential. | Yes, internal adapter and cookie migration. |
| Runtime baselines | Use Java 21 LTS, Node 22 LTS, PostgreSQL 16, and Docker Compose v2. Commit exact Gradle wrapper, package lock, and container image digest values during foundation work. | Stable current LTS floor with reproducible builds; exact compatible framework/image patch values are captured rather than guessed now. | Yes, normal upgrade path. |
| Input limits | Enforce title 120 Unicode code points; organization 120; job title 120; name 80; admin email 254. Reject rather than truncate. | Makes DB/API/UI boundaries testable while comfortably serving stated use. | Yes, validation/schema change. |
| Public rate limits | Configure valid-link limits per `(board_id, share_link_version, client_ip, operation)`: identify 90/minute and submit 40/minute, plus a separately bounded invalid-link/IP bucket. | Allows shared-event NAT bursts and retries while preventing board-version collisions and unbounded attacker keys; no per-roster lockout. | Yes, configuration only. |
| Release promotion | Push immutable `vX.Y.Z` GHCR images, then atomically compensate paired `production` aliases through a serialized promotion workflow; Portainer's fixed `IMAGE_TAG=production` stack pulls only after both aliases verify. Redeploying an existing immutable tag uses the same rollback path. | A webhook cannot change a Portainer stack variable, while immutable tags preserve traceability and paired compensation prevents mixed retained aliases. | Yes, documented deployment convention. |
| Public mutation defense | Use CSRF for every mutation, including login, identify, and signature submission; `XSRF-TOKEN` is readable but is not authentication data. | The same-origin cookie deployment supports one consistent anti-CSRF contract without guessing between CSRF and Origin checks. | Yes, internal protocol change. |
| Physical device verification | Require actual iPad Safari and Android Chrome evidence as an external release gate; browser emulation is supporting evidence only. | The approved acceptance matrix explicitly names real devices and no device-control bridge is currently guaranteed. | Yes, when a bridge becomes available. |

## Findings (cited - path:lines)

- The repository is greenfield: there is no source tree, infrastructure, CI, migration, or test surface yet. The requested structure and phase ordering are explicit in `docs/plan/IMPLEMENTATION_PLAN.md:67-84, 409-457`.
- Product authority is `docs/plan/PRODUCT_PLAN.md`; the implementation plan must not add public signup, email workflows, multi-admin support, legal-signature claims, public board view, smartphone support, automatic backups, or login-failure notifications.
- One board belongs to one event, has at most 50 rostered people, and targets P95 at or below 25 actual signers; only desktop admin and iPadOS 16+/Android 12+ tablet paths are acceptance surfaces. `docs/plan/PRODUCT_PLAN.md:7-17`; `docs/plan/IMPLEMENTATION_PLAN.md:15-21`.
- The public deployment is a same-origin frontend proxy through NPM; backend, PostgreSQL, and MinIO must remain private. `docs/plan/IMPLEMENTATION_PLAN.md:41-65`; `docs/REVERSE_PROXY_SETUP_GUIDE.md:66-129`.
- Spring Session tables must be created by explicit Flyway migrations, Compose needs PostgreSQL health checks plus `service_healthy`, and SSE must be tested through Nginx buffering/timeout configuration. These are execution safeguards, not new product scope.
- Mandatory Metis audit was completed after approval. Its release-tag, physical-device, CSRF, race, session-matrix, encryption-cleanup, capacity, and deployment-prerequisite gaps are resolved in `.omo/plans/narae-signing-web-mvp.md` tasks 7-30.
- Git ground truth: `main` is unborn and only approved `docs/`/`.omo/` artifacts are untracked. The OMO plan now requires a constrained initial documentation baseline commit before any implementation branch begins.

## Decisions (with rationale)

- Use the approved Vite/React/TypeScript and Spring Boot modular-monolith split. The small single-instance event workload does not justify Redis, queues, microservices, or a shared frontend/backend types package.
- Treat every server mutation as authoritative and atomic: revalidate board status, ownership/share link, roster assignment, slot revision, and prior submission in the database transaction. First valid submission wins.
- Keep all raw roster values and signature vectors encrypted with versioned AES-256-GCM, use purpose-separated HMAC for exact identity matching, and fail production startup when the mounted master key is absent or invalid.
- Treat SSE as an invalidation hint only. Clients reconnect with backoff and refetch authorized REST snapshots, so missed events never determine correctness.
- Render closed-board PNGs on demand using the same normalized-coordinate rules as the interactive board. A single server-global render semaphore returns `503 Retry-After` to concurrent requests.
- Block access before permanent deletion, persist the object purge job, and retry MinIO deletion until confirmed. Old links receive a non-disclosing unavailable response.
- Serialize every board mutation with board-then-slot locks, use conditional first-submit update, bind encrypted values to entity/field AAD, and retain durable pending/retired asset cleanup to close concurrency/orphan gaps.
- Execute feature work only on function-unit branches with real per-todo commits. Wave 5A may use three parallel subagents only in isolated worktrees, after path ownership and dependency checks, and only merge after a disposable conflict-proof test succeeds.
- Persist a current share token as lookup hash plus versioned AES-GCM ciphertext so owner-only editor reload/QR recovery is possible without application-controlled browser storage; the public lookup still uses only the hash.
- Derive lockout and public-rate client IP from one NPM-overwrite → frontend-source-restriction → backend-static-peer chain, never a user-controlled forwarding chain.
- Use PostgreSQL post-commit `NOTIFY`/`LISTEN` for cross-process administrator-session invalidation, with per-send JDBC reauthorization as fallback.

## Scope IN

- Admin account operations, auth/session controls, board lifecycle, roster authoring/import, slots, backgrounds, share links/QR, public identity and signing, full view, final PNG, encryption, Compose/Portainer/NPM deployment artifacts, CI, and listed QA.

## Scope OUT (Must NOT have)

- Public admin enrollment, email verification/reset, SMTP, multiple-admin workflows, ownership transfer, legal e-signature/audit trails, smartphone signing, multilingual UI, public full board view, non-PNG export, external backup/recovery automation, login-failure alerting, Redis, queues, microservices, public MinIO, or visual-token work reserved for `DESIGN.md`.

## Open questions

None. Deployment-time values remain prerequisites, not product decisions: `APP_LXC_IP`, `NPM_LXC_IP`, `PUBLIC_IPV4`, DNS/NAT authority, Portainer/GHCR credentials, MinIO/database secrets, and the root-only master-key file.

## Approval gate

status: complete

Plan created after explicit owner approval. No implementation has started. The owner then explicitly requested a high-accuracy review; execution remains paused until that review reaches a terminal outcome.

## High-accuracy review

```json
{
  "phase": "review_round_initialized",
  "review_required": true,
  "plan_path": ".omo/plans/narae-signing-web-mvp.md",
  "plan_sha256": "3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca",
  "review_round_id": "narae-signing-web-mvp-20260820-r33",
  "round_status": "approved",
  "pending-action": "none",
  "prior_rounds": [
    {
      "round_id": "narae-signing-web-mvp-20260819-r2",
      "plan_sha256": "3a82452812835052ee710e61e957c4dad595db184521f25b4340c339082dc2d1",
      "momus": "OKAY",
      "independent": "CHANGES_REQUESTED: execution DAG/ownership, release identity and paired-promotion recovery, post-invalidation SSE, rate-key bounds, token-log/referrer controls, range-based final audit, manual-gate labeling, executable release probes, and import/lockout concurrency contracts",
      "resolution": "Plan revised; r3 rechecks the new checksum. The r2 disposable workspace omitted the cited draft, so r3 includes plan, docs, and draft."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r3",
      "plan_sha256": "31bfe8b2c72ffefe7a76aa7e208695969eb73a29a6dc375ff3f53356369b2f56",
      "momus": "CHANGES_REQUESTED: Todo 29 branch merge evidence was circular because its complete candidate suite requires merged main.",
      "independent": "INCONCLUSIVE: isolated Codex runtime had no bearer authentication; no reviewer result was accepted.",
      "resolution": "Todo 29 now has explicit provisional branch proof followed by required post-merge candidate proof. r4 uses a disposable workspace with the authenticated independent runtime and plan/docs/draft copied in."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r4",
      "plan_sha256": "d79d55396a702021cead9097f9f021aa2338289526e3b25fb5dc0f3e2cc547c7",
      "momus": "OKAY: bound checksum, ownership/DAG, promotion, SSE, limiter, parser, evidence, and external gate were executable.",
      "independent": "CHANGES_REQUESTED: Todo 30/tag lifecycle, recoverable share-token storage, executable trusted-client-IP chain, cross-process reset-to-SSE invalidation, promotion restoration branch coverage, truthful blocked ledger states, and import source/field bounds.",
      "resolution": "Plan revised to d1803ded998eaf024c93579b4f95c1be37e2e7e909d675aea8d97710c5bcf032. Todo 30 is external evidence only; Todo 28 commits its checklist; share token is AES-GCM recoverable for owner GET /share; NPM/frontend/backend provenance and PostgreSQL NOTIFY/LISTEN are explicit; importer/release/ledger contracts were tightened. Fresh r5 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r5",
      "plan_sha256": "d1803ded998eaf024c93579b4f95c1be37e2e7e909d675aea8d97710c5bcf032",
      "momus": "OKAY: all r4 corrections are executable at the bound checksum.",
      "independent": "CHANGES_REQUESTED: candidate recorder required Todo 29 and its own receipt before creating them; rollback reused the new-tag checkpoint even though existing tags are rejected and images would rebuild.",
      "resolution": "Plan revised to 98795807d9e4da39dbd41641664b34e2f7900788f4e0dcd0bd31e315dd05ef9f. Candidate recorder now requires Todos 1-28 plus Todo 29 provisional/matrix proof, then writes the receipt and completes Todo 29; rollback/redeploy verifies an existing immutable pair and promotes aliases without candidate/tag/build/push. Fresh r6 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r6",
      "plan_sha256": "98795807d9e4da39dbd41641664b34e2f7900788f4e0dcd0bd31e315dd05ef9f",
      "momus": "OKAY: candidate-recorder ordering and immutable rollback/redeploy path are executable at the bound checksum.",
      "independent": "CHANGES_REQUESTED: candidate receipt/ledger publication was not crash-consistent; the new-image path lacked empty-registry proof and partial-push recovery; evidence verification lacked branch/DAG/path/merge binding; encrypted current share-token docs remained incomplete; and roster import lacked a row-specific UI/error proof.",
      "resolution": "Plan revised to fb09853591a0ae7f5c5579de12e1799f97b69fdcf1bc5c16650cc3bae69a3e84. It now has a locked receipt-first repair protocol, image version-burn handling with empty-registry/partial-push tests, branch provenance and owned-path verification, four-section share-token documentation reconciliation, and bounded privacy-safe row errors rendered by the roster panel. Fresh r7 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r7",
      "plan_sha256": "fb09853591a0ae7f5c5579de12e1799f97b69fdcf1bc5c16650cc3bae69a3e84",
      "momus": "OKAY: branch/ledger provenance, locked candidate repair, version-burn release behavior, cross-document reconciliation, and external gate were executable at the bound SHA.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fb09853591a0ae7f5c5579de12e1799f97b69fdcf1bc5c16650cc3bae69a3e84; round_identity=narae-signing-web-mvp-20260819-r7; launch_identity=momus-narae-signing-web-mvp-20260819-r7-1",
      "independent": "CHANGES_REQUESTED: broken draft references; zero-coordinate geometry; Todo 8/10 dependency; overlapping ownership; provenance/ledger durability; immutable-publish serialization; CSRF/session-document drift; initial background behavior; and rollout harness/dispatch gaps.",
      "independent_receipt": "workspace_root=/tmp/narae-signing-plan-review.oE8bos/workspace; runtime_home=/tmp/narae-signing-plan-review.oE8bos/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fb09853591a0ae7f5c5579de12e1799f97b69fdcf1bc5c16650cc3bae69a3e84; round_identity=narae-signing-web-mvp-20260819-r7; launch_identity=independent-narae-signing-web-mvp-20260819-r7-2; session_identity=/root; process_identity=receipt-shell:51900,parent:46730",
      "resolution": "Plan revised to 949501efdefe673f112e8b992c91eb43f4261cfa8f9ed31119476bf111541f1e. It removes broken draft references; accepts zero slot coordinates; separates Todo 8 QA from Todo 10; makes ownership exact; binds every commit to branch-tip interval coverage; makes receipt/ledger publication crash-safe; serializes immutable publishing; reconciles CSRF/session fields; fixes first-image canvas adoption; and names executable deployment harnesses. Fresh r8 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r8",
      "plan_sha256": "949501efdefe673f112e8b992c91eb43f4261cfa8f9ed31119476bf111541f1e",
      "momus": "CHANGES_REQUESTED: numeric Todo ordering contradicted Todo 8's dependency on Todo 9; branch provenance must use an explicit dependency-topological sequence.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=949501efdefe673f112e8b992c91eb43f4261cfa8f9ed31119476bf111541f1e; round_identity=narae-signing-web-mvp-20260819-r8; launch_identity=momus-narae-signing-web-mvp-20260819-r8-1; session_identity=/root/momus_high_accuracy_plan_r4; process_identity=codex-agent (OS PID not exposed).",
      "independent": "CHANGES_REQUESTED: global commit-set/topology closure, final-fsync semantics, CSRF section 10 reconciliation, exact dependency/manifests/test ownership, and response-only slotAspectRatio were missing.",
      "independent_receipt": "workspace_root=/tmp/narae-signing-plan-review.aP9YdW/workspace; runtime_home=/tmp/narae-signing-plan-review.aP9YdW/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=949501efdefe673f112e8b992c91eb43f4261cfa8f9ed31119476bf111541f1e; round_identity=narae-signing-web-mvp-20260819-r8; launch_identity=independent-narae-signing-web-mvp-20260819-r8-2; session_or_process_identity=/root; evidence_shell_pid=83453.",
      "resolution": "Plan revised to 9baff8a028d01a63d3d8724b175f3f548c5f3f81c02157cd120cb59bc9117e05. It defines per-branch dependency-topological commit_order, rejects direct-main/unrecorded merges through global range equality, distinguishes pre-final from post-final-fsync recovery, reconciles all CSRF sections, grants exact serial manifests/tests/docs ownership, and adds derived response-only slotAspectRatio. Fresh r9 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r9",
      "plan_sha256": "9baff8a028d01a63d3d8724b175f3f548c5f3f81c02157cd120cb59bc9117e05",
      "momus": "OKAY: no concrete executable defects found.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=9baff8a028d01a63d3d8724b175f3f548c5f3f81c02157cd120cb59bc9117e05; round_identity=narae-signing-web-mvp-20260819-r9; launch_identity=momus-narae-signing-web-mvp-20260819-r9-1; session_identity=/root/momus_high_accuracy_plan_r4; process_identity=codex-agent.",
      "independent": "CHANGES_REQUESTED: candidate durable sealing, mutation-only CSRF, all-section documentation recheck, exact Todo 29 ownership, Dockerfile ownership, signature numeric/range validation, deletion documentation reconciliation, and branch-protocol/matrix alignment.",
      "independent_receipt": "workspace_root=/tmp/narae-signing-plan-review.ER07eq/workspace; runtime_home=/tmp/narae-signing-plan-review.ER07eq/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=9baff8a028d01a63d3d8724b175f3f548c5f3f81c02157cd120cb59bc9117e05; round_identity=narae-signing-web-mvp-20260819-r9; launch_identity=independent-narae-signing-web-mvp-20260819-r9-2; session_or_process_identity=process:6445",
      "resolution": "Plan revised to fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c. It adds a shared-lock durable sealing protocol and failpoint proof, mutation-only CSRF wording plus a three-section recheck, exact test/Dockerfile ownership, strict signature schema validation, deletion-order documentation reconciliation, and a branch-protocol-consistent matrix. Fresh r10 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r10",
      "plan_sha256": "fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c",
      "momus": "OKAY, invalidated: independent lane never started, so this approval cannot be paired or reused.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c; round_identity=narae-signing-web-mvp-20260819-r10; launch_identity=momus-narae-signing-web-mvp-20260819-r10-1; session_or_process_identity=pid:26008;agent:/root/momus_high_accuracy_plan_r10",
      "independent": "INCONCLUSIVE: codex exec stopped before reviewer startup because disposable copy was not a Git repository; no independent review result accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.myO4mL/workspace; runtime_home=/private/tmp/narae-signing-plan-review.myO4mL/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c; round_identity=narae-signing-web-mvp-20260819-r10; launch_identity=independent-narae-signing-web-mvp-20260819-r10-2; session_or_process_identity=null",
      "resolution": "No plan edit. Round invalidated by independent launch interruption. Fresh r11 uses a disposable Git-initialized workspace and isolated runtime home."
    },
    {
      "round_id": "narae-signing-web-mvp-20260819-r11",
      "plan_sha256": "fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c",
      "momus": "CHANGES_REQUESTED: Playwright config/project setup was created after tasks that invoke it; Todo 16 preclaimed later API-controller paths.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c; round_identity=narae-signing-web-mvp-20260819-r11; launch_identity=momus-narae-signing-web-mvp-20260819-r11-1; session_or_process_identity=pid:32312",
      "independent": "INCONCLUSIVE: isolated CODEX_HOME had no authentication material; Codex returned HTTP 401 before the required first plan read.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.RZi55v/workspace; runtime_home=/private/tmp/narae-signing-plan-review.RZi55v/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=fd23ede2932b7fa7c01429382af4e91ad32d283aab0c8857ddc02565f8a51d0c; round_identity=narae-signing-web-mvp-20260819-r11; launch_identity=independent-narae-signing-web-mvp-20260819-r11-2; session_or_process_identity=codex_session=01a01a86-7f69-7902-a5f6-02c9652e82e2",
      "resolution": "Plan revised to 26499da2ea06b1a514ca11b09fbb764c791de0f5f1b2a1677fa1f7f881095926. Todo 5 now pins/configures baseline Chromium/WebKit and Todo 6 installs/runs it; Todo 29 extends it serially. Todo 16 no longer owns later routes, whose module controllers are explicit. Fresh r12 dual review uses a private copied auth file in its otherwise isolated runtime."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r12",
      "plan_sha256": "26499da2ea06b1a514ca11b09fbb764c791de0f5f1b2a1677fa1f7f881095926",
      "momus": "OKAY: task ordering, ownership, QA, security/session, release recovery, and cited documentation align.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=26499da2ea06b1a514ca11b09fbb764c791de0f5f1b2a1677fa1f7f881095926; round_identity=narae-signing-web-mvp-20260820-r12; launch_identity=momus-narae-signing-web-mvp-20260820-r12-1; session_or_process_identity=44313",
      "independent": "CHANGES_REQUESTED: signature aspect/session binding; exact Playwright ownership; ingress/body limits; crash-idempotent tag creation; durable shared provenance; roster-slot replacement; admin canonicalization/BCrypt bounds; lockout-state bounds; exact adopted image dimensions; and cache headers.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.z0ST2w/workspace; runtime_home=/private/tmp/narae-signing-plan-review.z0ST2w/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=26499da2ea06b1a514ca11b09fbb764c791de0f5f1b2a1677fa1f7f881095926; round_identity=narae-signing-web-mvp-20260820-r12; launch_identity=independent-narae-signing-web-mvp-20260820-r12-2; session_or_process_identity=intake-pid:42471",
      "resolution": "Plan revised to 8149c9eb95318d29c4264a6b8c8678a53badfeaaf1f80c02cdb4a68dada0547d. It binds pixel-aware signature aspect to the signer session; gives every browser command an owned test; defines end-to-end body/cache limits; adds tag-intent recovery and atomic coordinator provenance; reconciles roster replacement; and bounds/canonicalizes authentication. Fresh r13 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r13",
      "plan_sha256": "8149c9eb95318d29c4264a6b8c8678a53badfeaaf1f80c02cdb4a68dada0547d",
      "momus": "OKAY: SHA-256 matches; DAG, exclusive ownership, r12 corrections, source references, QA/release evidence, and scope guardrails are executable.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=<unset>; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8149c9eb95318d29c4264a6b8c8678a53badfeaaf1f80c02cdb4a68dada0547d; round_identity=narae-signing-web-mvp-20260820-r13; launch_identity=momus-narae-signing-web-mvp-20260820-r13-1; session_or_process_identity=/root/momus_high_accuracy_plan_r13; shell_pid=83150",
      "independent": "CHANGES_REQUESTED: bootstrap deadlock; sole-TSV-writer contradiction; evidence digest/durability and deployment publication gaps; premature final-PNG verification in Todo 19; unreconciled section 5.4 signature geometry; incomplete ASCII email grammar and global IP-cap race; stale frontmatter SHA.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.VDeCuN/workspace; runtime_home=/private/tmp/narae-signing-plan-review.VDeCuN/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8149c9eb95318d29c4264a6b8c8678a53badfeaaf1f80c02cdb4a68dada0547d; round_identity=narae-signing-web-mvp-20260820-r13; launch_identity=independent-narae-signing-web-mvp-20260820-r13-2; session_or_process_identity=/root",
      "resolution": "Plan revised to dd3c748b47f083157f42038b92b22a65cb4e2d00b19edb9558b98c22d2cce71a. It makes foundation bootstrap explicit, assigns TSV versus non-TSV writers, binds/seals evidence digests, moves final-PNG proof to Todo 25, reconciles source/API ownership for aspect/background behavior, serializes the global IP cap, and synchronizes draft frontmatter. Fresh r14 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r14",
      "plan_sha256": "dd3c748b47f083157f42038b92b22a65cb4e2d00b19edb9558b98c22d2cce71a",
      "momus": "OKAY: checksum-bound executable-plan review passed.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=dd3c748b47f083157f42038b92b22a65cb4e2d00b19edb9558b98c22d2cce71a; round_identity=narae-signing-web-mvp-20260820-r14; launch_identity=momus-narae-signing-web-mvp-20260820-r14-1; session_or_process_identity=/root/momus_high_accuracy_plan_r14",
      "independent": "CHANGES_REQUESTED: candidate receipt/row-digest circular dependency; candidate release omits frontend lint/typecheck/build before promotion; tag intent lacks exact recreatable annotated-tag payload; stale Task 19 canvasMode schema conflicts with the separate adoption route.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.xmLsa4/workspace; runtime_home=/private/tmp/narae-signing-plan-review.xmLsa4/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=dd3c748b47f083157f42038b92b22a65cb4e2d00b19edb9558b98c22d2cce71a; round_identity=narae-signing-web-mvp-20260820-r14; launch_identity=independent-narae-signing-web-mvp-20260820-r14-2; session_or_process_identity=validator_pid=10837",
      "resolution": "Plan revised to 36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad. It makes candidate linkage one-way, runs F1/F2/F4 candidate gates before tag/promotion, persists complete tag-object payload, and removes stale Task 19 multipart contract. Fresh r15 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r15",
      "plan_sha256": "36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad",
      "momus": "OKAY: checksum-bound artifact verified.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad; round_identity=narae-signing-web-mvp-20260820-r15; launch_identity=momus-narae-signing-web-mvp-20260820-r15-1; session_or_process_identity=pid:36862",
      "independent": "INCONCLUSIVE: required intake never executed because read-only runtime could not create a heredoc temp file before root descriptor open; no plan findings asserted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.RPlhwE/workspace; runtime_home=/private/tmp/narae-signing-plan-review.RPlhwE/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad; round_identity=narae-signing-web-mvp-20260820-r15; launch_identity=independent-narae-signing-web-mvp-20260820-r15-2; session_or_process_identity=pid:35948",
      "resolution": "No plan edit. Round invalidated because isolated read-only intake failed before artifact access. Fresh r16 uses a disposable workspace-write sandbox with explicit no-edit instructions so the mandatory descriptor intake can execute."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r16",
      "plan_sha256": "36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad",
      "momus": "OKAY: checksum-bound executable-plan review passed.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad; round_identity=narae-signing-web-mvp-20260820-r16; launch_identity=momus-narae-signing-web-mvp-20260820-r16-1; session_or_process_identity=codex-agent:/root/momus_high_accuracy_plan_r16",
      "independent": "CHANGES_REQUESTED: PostgreSQL advisory-lock NUL encoding invalid; row_digest serialization/selectors undefined; XSRF-TOKEN cookie contract incomplete; admin lifecycle UI controls unowned; pre-promotion reports not tag-required; post-deployment failure lacks mandatory recovery/evidence.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.PokJrt/workspace; runtime_home=/private/tmp/narae-signing-plan-review.PokJrt/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=36d1fcd59c2b5de3f4b16da19fb2ed7de22ca831db35da65b09cee264c4630ad; round_identity=narae-signing-web-mvp-20260820-r16; launch_identity=independent-narae-signing-web-mvp-20260820-r16-2; session_or_process_identity=pid:41609",
      "resolution": "Plan revised to 0263991a3c79f6c85ede15d5d067c8a058b7f8a81991ef2efbe7397b5fc5e2c4. It defines valid lock/evidence encodings, exact CSRF cookie behavior, lifecycle UI ownership, a tag-required candidate-gate receipt, and mandatory post-deploy recovery outcomes. Fresh r17 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r17",
      "plan_sha256": "0263991a3c79f6c85ede15d5d067c8a058b7f8a81991ef2efbe7397b5fc5e2c4",
      "momus": "OKAY: checksum-bound artifact verified; tasks, dependencies, references, acceptance criteria, and concrete QA scenarios are executable.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=0263991a3c79f6c85ede15d5d067c8a058b7f8a81991ef2efbe7397b5fc5e2c4; round_identity=narae-signing-web-mvp-20260820-r17; launch_identity=momus-narae-signing-web-mvp-20260820-r17-1; session_or_process_identity=pid-63460",
      "independent": "CHANGES_REQUESTED: runner-to-coordinator snapshot durability, production lease through probes/recovery, webhook ambiguity/crash reconciliation, and prior-image/Flyway rollback compatibility.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.92yqus/workspace; runtime_home=/private/tmp/narae-signing-plan-review.92yqus/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=0263991a3c79f6c85ede15d5d067c8a058b7f8a81991ef2efbe7397b5fc5e2c4; round_identity=narae-signing-web-mvp-20260820-r17; launch_identity=independent-narae-signing-web-mvp-20260820-r17-2; session_or_process_identity=pid:70803",
      "resolution": "Plan revised to 94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b. It uploads and retains the pre-promotion snapshot/operation journal, makes one workflow lease cover delivery through terminal outcome, reconciles webhook-ambiguous/lost-run states, and requires prior-image compatibility against candidate Flyway schema. Fresh r18 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r18",
      "plan_sha256": "94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b",
      "momus": "OKAY, invalidated: independent lane reached INCONCLUSIVE before review, so this approval cannot be paired or reused.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b; round_identity=narae-signing-web-mvp-20260820-r18; launch_identity=momus-narae-signing-web-mvp-20260820-r18-1; session_or_process_identity=pid-91512",
      "independent": "INCONCLUSIVE: descriptor-bound intake SHA matched, but full plan bytes were emitted and transport truncation made retrieval incomplete; no plan review or findings accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.hdifPJ/workspace; runtime_home=/private/tmp/narae-signing-plan-review.hdifPJ/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b; round_identity=narae-signing-web-mvp-20260820-r18; launch_identity=independent-narae-signing-web-mvp-20260820-r18-2; session_or_process_identity=pid:94953",
      "resolution": "No plan edit. Round invalidated because independent intake output was truncated before review. Fresh r19 uses the same descriptor-chain validation but emits only its receipt, then reads the exact bound target in bounded chunks."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r19",
      "plan_sha256": "94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b; round_identity=narae-signing-web-mvp-20260820-r19; launch_identity=momus-narae-signing-web-mvp-20260820-r19-1; session_or_process_identity=pid-97912",
      "independent": "CHANGES_REQUESTED: unresolved manual/recovery terminal states could allow a different tag; selected arbitrary rollback lacked current-schema proof; deployment prerequisites were checklist-only; Todo 11 had an undeclared frontend route ownership conflict; and candidate reports lacked a machine-verifiable producer/command/exit/candidate schema.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.EydeBN/workspace; runtime_home=/private/tmp/narae-signing-plan-review.EydeBN/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b; round_identity=narae-signing-web-mvp-20260820-r19; launch_identity=independent-narae-signing-web-mvp-20260820-r19-2; session_or_process_identity=intake_process_id:99438;final_verification_process_id:5336; final_sha256=94a45c479aecc563a76b00069414c7531b358cc3c01d824f37f24f73e4a6390b; final_byte_count=195890",
      "resolution": "Plan revised to bdbf3c7ef38e05c339094daf8d38975c49e2dab600fb38af6752cf5274578882: machine-verified recovery clearance blocks every different-tag action, selected rollback verifies its exact image against the current schema, an expiring sealed live-prerequisite workflow gate precedes tagging, route ownership/handoff is explicit, and candidate gates use sealed versioned command reports. Fresh r20 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r20",
      "plan_sha256": "bdbf3c7ef38e05c339094daf8d38975c49e2dab600fb38af6752cf5274578882",
      "momus": "CHANGES_REQUESTED: selected rollback rebuilt a schema from the currently running image instead of the actual live production schema after a failed candidate may already have migrated it.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=bdbf3c7ef38e05c339094daf8d38975c49e2dab600fb38af6752cf5274578882; round_identity=narae-signing-web-mvp-20260820-r20; launch_identity=momus-narae-signing-web-mvp-20260820-r20-1; session_or_process_identity=pid-18238",
      "independent": "NOT_RUN: isolated Codex exited before audit because its copied workspace had no Git repository and the launch omitted `--skip-git-repo-check`; no findings or approval accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.XEgbAH/workspace; runtime_home=/private/tmp/narae-signing-plan-review.XEgbAH/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=bdbf3c7ef38e05c339094daf8d38975c49e2dab600fb38af6752cf5274578882; round_identity=narae-signing-web-mvp-20260820-r20; launch_identity=independent-narae-signing-web-mvp-20260820-r20-2; session_or_process_identity=not_started; final_sha256=not_audited; final_byte_count=not_audited",
      "resolution": "Plan revised to 310c6c4c064ff34c644bc84d84a8f2e752e411c32a5ab0a15870612eeffc89f6: selected rollback now clones and hashes the actual live production schema plus Flyway history under a consistent read, uses only synthetic smoke data, trap-deletes the ephemeral clone, and rejects image-regenerated schema proof. Fresh r21 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r21",
      "plan_sha256": "310c6c4c064ff34c644bc84d84a8f2e752e411c32a5ab0a15870612eeffc89f6",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=310c6c4c064ff34c644bc84d84a8f2e752e411c32a5ab0a15870612eeffc89f6; round_identity=narae-signing-web-mvp-20260820-r21; launch_identity=momus-narae-signing-web-mvp-20260820-r21-1; session_or_process_identity=pid-23245",
      "independent": "CHANGES_REQUESTED: protected-tag/workflow authorization could bypass candidate/live gates; rollback attempts could collide by tag/candidate; artifact retention was incorrectly authoritative; Todos 17/23 lacked route-hunk ownership; merge publication was not crash-safe.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.r7MkBZ/workspace; runtime_home=/private/tmp/narae-signing-plan-review.r7MkBZ/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=310c6c4c064ff34c644bc84d84a8f2e752e411c32a5ab0a15870612eeffc89f6; round_identity=narae-signing-web-mvp-20260820-r21; launch_identity=independent-narae-signing-web-mvp-20260820-r21-2; session_or_process_identity=codex_thread_id:01a01b0e-aed3-7943-9d8a-f3070f81a3d5; final_sha256=310c6c4c064ff34c644bc84d84a8f2e752e411c32a5ab0a15870612eeffc89f6; final_byte_count=209168",
      "resolution": "Plan revised to b761d6254d5b9c27c33817a477c393ad94572deb601bf84de401afbae0a624fd: journal-bound release authorization/capabilities, unique operation-attempt identities, persistent recovery journal authority, route-hunk contracts, and sealed CAS merge intents were added. Fresh r22 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r22",
      "plan_sha256": "b761d6254d5b9c27c33817a477c393ad94572deb601bf84de401afbae0a624fd",
      "momus": "CHANGES_REQUESTED: a hosted immutable build cannot verify a private HMAC/journal capability without receiving forgery power.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=b761d6254d5b9c27c33817a477c393ad94572deb601bf84de401afbae0a624fd; round_identity=narae-signing-web-mvp-20260820-r22; launch_identity=momus-narae-signing-web-mvp-20260820-r22-1; session_or_process_identity=pid-unknown",
      "independent": "INCONCLUSIVE: read-only sandbox blocked mandatory descriptor-intake heredoc creation before review; no findings accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.MAUeQ5/workspace; runtime_home=/private/tmp/narae-signing-plan-review.MAUeQ5/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=b761d6254d5b9c27c33817a477c393ad94572deb601bf84de401afbae0a624fd; round_identity=narae-signing-web-mvp-20260820-r22; launch_identity=independent-narae-signing-web-mvp-20260820-r22-2; session_or_process_identity=unavailable-intake-shell; final_sha256=unavailable; final_byte_count=unavailable",
      "resolution": "Plan revised to 8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd: immutable GHCR build/push moved to private narae-release jobs; hosted external-probe has no journal/capability/key/GHCR/Portainer access; capability now binds workflow run and operation attempt. Fresh r23 uses workspace-write sandbox for independent intake while retaining normal sandboxing and no approval bypass."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r23",
      "plan_sha256": "8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd",
      "momus": "OKAY, invalidated because the independent lane was inconclusive before a full audit.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; round_identity=narae-signing-web-mvp-20260820-r23; launch_identity=momus-narae-signing-web-mvp-20260820-r23-1; session_or_process_identity=pid-54988",
      "independent": "INCONCLUSIVE: checksum-bound intake succeeded but full file retrieval stopped after lines 121-240; no findings accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.oHOADE/workspace; runtime_home=/private/tmp/narae-signing-plan-review.oHOADE/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; round_identity=narae-signing-web-mvp-20260820-r23; launch_identity=independent-narae-signing-web-mvp-20260820-r23-2; session_or_process_identity=pid-55996; final_sha256=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; final_byte_count=226540",
      "resolution": "No plan edit. Fresh r24 requires full descriptor-bound plan coverage through EOF; its isolated lane uses normal workspace-write sandbox solely for disposable intake scratch work."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r24",
      "plan_sha256": "8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; round_identity=narae-signing-web-mvp-20260820-r24; launch_identity=momus-narae-signing-web-mvp-20260820-r24-1; session_or_process_identity=pid-59681",
      "independent": "CHANGES_REQUESTED: coordinator worktree refresh, capability ratchet, orphan recovery, private recovery-clearance finalizer, Compose/LXC capacity limits, and `.codegraph` baseline behavior were missing; hosted release boundary passed.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.aQ6psB/workspace; runtime_home=/private/tmp/narae-signing-plan-review.aQ6psB/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; round_identity=narae-signing-web-mvp-20260820-r24; launch_identity=independent-narae-signing-web-mvp-20260820-r24-2; session_or_process_identity=exec-shell-pid-61743; final_sha256=8a6983b5c0f9fd36930e8bda53d8cb8f346b81ec0b1c56440d8377b215563dbd; final_byte_count=226540; coverage=1-592",
      "resolution": "Plan revised to 70d16b7ca649a0ac950e50af31a526f8d2b6de3bbeafe20786b79657359ce2e3: managed detached worktrees and post-CAS refresh, journal orphan recovery plus descendant ratchets, a private clearance finalizer, exact Compose/LXC capacity gates, and `.codegraph` allowlist isolation were added. Fresh r25 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r25",
      "plan_sha256": "70d16b7ca649a0ac950e50af31a526f8d2b6de3bbeafe20786b79657359ce2e3",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=70d16b7ca649a0ac950e50af31a526f8d2b6de3bbeafe20786b79657359ce2e3; round_identity=narae-signing-web-mvp-20260820-r25; launch_identity=momus-narae-signing-web-mvp-20260820-r25-1; session_or_process_identity=pid-62323",
      "independent": "CHANGES_REQUESTED: retained evidence paths, candidate namespace/retry contract, application-LXC build headroom, offline master-key recovery, and ordinary replacement final-PNG proof were incomplete; hosted-job boundary passed.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.iWjkfP/workspace; runtime_home=/private/tmp/narae-signing-plan-review.iWjkfP/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=70d16b7ca649a0ac950e50af31a526f8d2b6de3bbeafe20786b79657359ce2e3; round_identity=narae-signing-web-mvp-20260820-r25; launch_identity=independent-narae-signing-web-mvp-20260820-r25-2; session_or_process_identity=Minjunui-MacBookPro.local:64523; final_sha256=70d16b7ca649a0ac950e50af31a526f8d2b6de3bbeafe20786b79657359ce2e3; final_byte_count=237455; coverage=1-605.",
      "resolution": "Plan revised to 8e51e44bcb1d7d322f62697862fa58fc455ce36ceb4d25634b1878d46e834201: detached release worktrees now use only absolute retained evidence paths, one candidate namespace/round policy is explicit, capacity includes sequential-build headroom, master-key recovery is manual/off-LXC and verified without secret retention, and ordinary mismatched-ratio background replacement has a final-PNG golden assertion. Fresh r26 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r26",
      "plan_sha256": "8e51e44bcb1d7d322f62697862fa58fc455ce36ceb4d25634b1878d46e834201",
      "momus": "OKAY, invalidated by independent CHANGES_REQUESTED on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=8e51e44bcb1d7d322f62697862fa58fc455ce36ceb4d25634b1878d46e834201; round_identity=narae-signing-web-mvp-20260820-r26; launch_identity=momus-narae-signing-web-mvp-20260820-r26-1; session_or_process_identity=pid-76375; coverage=1-609; final_wc=609-lines,246237-bytes",
      "independent": "CHANGES_REQUESTED: baseline parser and marker ownership, journal preimage, path-safe attempt ID, fresh rollback/redeploy authorization, retry-vs-source-change policy, runner-to-coordinator evidence transport, and hosted credential isolation needed exact contracts.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.pq0Oit/workspace; runtime_home=/private/tmp/narae-signing-plan-review.pq0Oit/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=sha256:8e51e44bcb1d7d322f62697862fa58fc455ce36ceb4d25634b1878d46e834201,bytes:246237; round_identity=narae-signing-web-mvp-20260820-r26; launch_identity=independent-narae-signing-web-mvp-20260820-r26-2; session_or_process_identity=agent-root+shell-pid-83043; final_sha256=8e51e44bcb1d7d322f62697862fa58fc455ce36ceb4d25634b1878d46e834201; final_byte_count=246237; coverage=1-609",
      "resolution": "Plan revised to 7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954: baseline values now have one no-follow parser path, Todo 19 marker ownership is exact, JCS journal self-hash/attempt naming/fresh authorization/retry policy are explicit, coordinator evidence transport is dispatcher-only, and hosted probes cannot receive administrator credentials. Fresh r27 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r27",
      "plan_sha256": "7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954",
      "momus": "CHANGES_REQUESTED: baseline checkpoint ordering and missing pre-authorization release preparation.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954; round_identity=narae-signing-web-mvp-20260820-r27; launch_identity=momus-narae-signing-web-mvp-20260820-r27-1; session_or_process_identity=pid-84495; final_sha=7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954; byte_count=255877; coverage=1-617",
      "independent": "CHANGES_REQUESTED: retained baseline-reader provenance, Todo 19 marker/contract ownership, branch-safe merge filenames, deploy/rollback preparation, retry policy, runner transport, hosted-job topology, journal codec wording, and outer NPM JSON 413 were incomplete.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.4b7WSe/workspace; runtime_home=/private/tmp/narae-signing-plan-review.4b7WSe/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954; round_identity=narae-signing-web-mvp-20260820-r27; launch_identity=independent-narae-signing-web-mvp-20260820-r27-2; session_or_process_identity=/root; final_sha256=7bd68621cdfe5dc604eeade8f257d343de4e13f50729dee84dc28e536898f954; final_byte_count=255877; coverage=1-617",
      "resolution": "Plan revised to badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61: release bootstrap uses the reader from clean current main against retained baseline data; Todo 1 predeclares markers and safe branch slugs; checkpoints explicitly prepare requests before authorization; retry, journal, artifact transport, hosted/private probes, and NPM JSON 413 are single-valued. Fresh r28 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r28",
      "plan_sha256": "badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61; round_identity=narae-signing-web-mvp-20260820-r28; launch_identity=momus-narae-signing-web-mvp-20260820-r28-1; session_or_process_identity=pid-86758; final_sha=badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61; byte_count=262288; coverage=1-617",
      "independent": "CHANGES_REQUESTED: detached-worktree validation, pre-promotion terminal state, fresh gate-attempt identity, and buffered chunked outer-NPM 413 needed exact contracts.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.Wamo1i/workspace; runtime_home=/private/tmp/narae-signing-plan-review.Wamo1i/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61; round_identity=narae-signing-web-mvp-20260820-r28; launch_identity=independent-narae-signing-web-mvp-20260820-r28-2; session_or_process_identity=pid:87023; final_sha256=badc0d4c45312a0e619b65aa0091cf2ba38266d08776521e43d96c3432f1ab61; final_byte_count=262288; coverage=1-617",
      "resolution": "Plan revised to c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b: all release tools require clean detached HEAD==main==candidate; each same-source validation has an immutable `val-` evidence namespace bound through tag/request/capability; pre-alias failures terminally burn the tag; NPM and frontend buffer mutation bodies before a zero-upstream exact JSON 413. Fresh r29 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r29",
      "plan_sha256": "c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b",
      "momus": "CHANGES_REQUESTED: validation helper output and checkpoint parsing had incompatible exact shapes.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b; round_identity=narae-signing-web-mvp-20260820-r29; launch_identity=momus-narae-signing-web-mvp-20260820-r29-1; session_or_process_identity=pid-87747; final_sha=c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b; byte_count=272151; coverage=1-629",
      "independent": "CHANGES_REQUESTED: validation stdout, same-attempt reauthorization, implementation-plan §11.1 ownership, active NPM buffering attestation, and prepare-operation worktree guard were incomplete.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.8iJQAS/workspace; runtime_home=/private/tmp/narae-signing-plan-review.8iJQAS/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b; round_identity=narae-signing-web-mvp-20260820-r29; launch_identity=independent-narae-signing-web-mvp-20260820-r29-2; session_or_process_identity=final-verifier-pid-87935; final_sha256=c601ef1c3b1574f5c691eeae9c7839edeb328304c85bcca27c84c57bce3f560b; final_byte_count=272151; coverage=1-629",
      "resolution": "Plan revised to d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070: raw validation stdout, exact deploy/rollback request worktree checks, same-attempt journal reauthorization, §11.1 selector ownership, and private live-NPM attestation are explicit. Fresh r30 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r30",
      "plan_sha256": "d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070; round_identity=narae-signing-web-mvp-20260820-r30; launch_identity=momus-narae-signing-web-mvp-20260820-r30-1; session_or_process_identity=pid-88596; final_sha=d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070; byte_count=277836; coverage=1-639",
      "independent": "CHANGES_REQUESTED: historical rollback/existing-tag redeploy lacked fresh operation-scoped live-prerequisite evidence; live NPM attestation omitted active header overwrite, token-log suppression, and SSE-only buffering.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.ozpQM5/workspace; runtime_home=/private/tmp/narae-signing-plan-review.ozpQM5/runtime-home; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070; round_identity=narae-signing-web-mvp-20260820-r30; launch_identity=independent-narae-signing-web-mvp-20260820-r30-2; session_or_process_identity=pid-88799; final_sha256=d5d1d39e31f3a83bceb993e936397e0a455fc7c6def3fbec4f0c5e5179dbc070; final_byte_count=277836; coverage=1-639",
      "resolution": "Plan revised to 5f17ebe3504d463f849fbcd249ad09d874285febe308c2cc5ed713f290c9cc6d: every operation now binds a fresh current-control-plane preflight while immutable tag authorization remains historical proof; live NPM attestation now covers header overwrite, token-log suppression, and SSE-only response buffering. Fresh r31 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r31",
      "plan_sha256": "5f17ebe3504d463f849fbcd249ad09d874285febe308c2cc5ed713f290c9cc6d",
      "momus": "CHANGES_REQUESTED: prior and override attestation clauses presented different assertion sets, despite the intended broader scope.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=5f17ebe3504d463f849fbcd249ad09d874285febe308c2cc5ed713f290c9cc6d; round_identity=narae-signing-web-mvp-20260820-r31; launch_identity=momus-narae-signing-web-mvp-20260820-r31-1; session_or_process_identity=pid-89348; final_sha256=5f17ebe3504d463f849fbcd249ad09d874285febe308c2cc5ed713f290c9cc6d; final_byte_count=282902; coverage=1-645",
      "independent": "INVALIDATED: interrupted after checksum intake because the source plan changed in response to the native finding; no full verdict accepted.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.0Mxeiy/workspace; runtime_home=global-default-with---ephemeral; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=5f17ebe3504d463f849fbcd249ad09d874285febe308c2cc5ed713f290c9cc6d; round_identity=narae-signing-web-mvp-20260820-r31; launch_identity=independent-narae-signing-web-mvp-20260820-r31-2; session_or_process_identity=codex_session=01a01ba8-3b57-7643-8fd7-fb7115d2b92a; final_sha256=not_audited; final_byte_count=not_audited; coverage=initial intake only",
      "resolution": "Plan revised to bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d: one canonical live-NPM attestation schema now directly covers all active header, logging, request-buffer, 413, and SSE response-buffer assertions. Fresh r32 dual review is required."
    },
    {
      "round_id": "narae-signing-web-mvp-20260820-r32",
      "plan_sha256": "bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d",
      "momus": "OKAY, invalidated by the independent CHANGES_REQUESTED verdict on the same checksum.",
      "momus_receipt": "workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d; round_identity=narae-signing-web-mvp-20260820-r32; launch_identity=momus-narae-signing-web-mvp-20260820-r32-1; session_or_process_identity=pid-89903; final_sha256=bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d; final_byte_count=282047; coverage=1-643",
      "independent": "CHANGES_REQUESTED: cold deploy lacked an absent-pair preflight state; attestation schema lacked exact wire shape; implementation-plan capacity and reverse-proxy rollback source text had no explicit reconciliation owner/assertion.",
      "independent_receipt": "workspace_root=/private/tmp/narae-signing-plan-review.PeAu6C/workspace; runtime_home=global-default-with---ephemeral; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d; round_identity=narae-signing-web-mvp-20260820-r32; launch_identity=independent-narae-signing-web-mvp-20260820-r32-2; session_or_process_identity=pid:90513; final_sha256=bd131eb0c27ce1969b592d6bbfdec917e1c5c26c70cd0bc98e9ef63b62558c6d; final_byte_count=282047; coverage=1-643",
      "resolution": "Plan revised to 3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca: the operation preflight now has an absent/matching/invalid immutable-pair matrix, NPM attestation has a closed JCS wire schema, and Todo 19/Todo 28 explicitly reconcile the capacity and rollback source sections. Fresh r33 dual review is required."
    }
  ],
  "review": {
    "momus": {
      "status": "OKAY",
      "workspace_root": "/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web",
      "runtime_home": null,
      "target": ".omo/plans/narae-signing-web-mvp.md",
      "round_id": "narae-signing-web-mvp-20260820-r33",
      "plan_sha256": "3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca",
      "launch_id": "momus-narae-signing-web-mvp-20260820-r33-1",
      "session": "/root/momus_high_accuracy_plan_r17",
      "result": "OKAY: Findings none. workspace_root=/Users/lapis0875/Projects/NaraeMedia/NaraeSigning-Web; runtime_home=null; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca; round_identity=narae-signing-web-mvp-20260820-r33; launch_identity=momus-narae-signing-web-mvp-20260820-r33-1; session_or_process_identity=pid-91509; final_sha256=3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca; final_byte_count=286943; coverage=1-650"
    },
    "independent": {
      "status": "OKAY",
      "workspace_root": "/private/tmp/narae-signing-plan-review.mkinji/workspace",
      "runtime_home": "global-default-with---ephemeral",
      "target": ".omo/plans/narae-signing-web-mvp.md",
      "round_id": "narae-signing-web-mvp-20260820-r33",
      "plan_sha256": "3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca",
      "launch_id": "independent-narae-signing-web-mvp-20260820-r33-2",
      "session": "codex-root-verifier-pid-91677",
      "result": "OKAY: Findings none. workspace_root=/private/tmp/narae-signing-plan-review.mkinji/workspace; runtime_home=global-default-with---ephemeral; target=.omo/plans/narae-signing-web-mvp.md; artifact_identity=3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca; round_identity=narae-signing-web-mvp-20260820-r33; launch_identity=independent-narae-signing-web-mvp-20260820-r33-2; session_or_process_identity=codex-root-verifier-pid-91677; final_sha256=3ed5972de8dbec61569319ae3d79204746133e695afbf8a4298a15cabc4ee6ca; final_byte_count=286943; coverage=1-650"
    }
  }
}
```
