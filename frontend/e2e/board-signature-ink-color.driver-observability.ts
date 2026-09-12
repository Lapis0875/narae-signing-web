import { z } from "zod";

const SaveUiShape = {
  startDisabled: z.boolean(),
  status: z.enum(["변경 없음", "저장 중", "저장됨", "저장 실패"]),
} as const;
const SaveUiObservationSchema = z.object(SaveUiShape).strict().readonly();
export type SaveUiObservation = z.infer<typeof SaveUiObservationSchema>;

const RecoveryObservationSchema = z
  .object({
    ...SaveUiShape,
    sameOwnerAttemptStartedAfterFailure: z.literal(true),
  })
  .strict()
  .readonly();

const QueuedCancellationEvidenceSchema = z
  .object({
    failureProvenanceLost: z.boolean(),
    patchStatusesBeforeRecovery: z.array(z.number().int()).readonly(),
    recovery: RecoveryObservationSchema,
    terminal: SaveUiObservationSchema,
    unresolved: SaveUiObservationSchema,
    violation: z.boolean(),
  })
  .strict()
  .readonly();
const SafeReplacementEvidenceSchema = z
  .object({
    phantomPending: z.boolean(),
    replacedAttemptPatchRequests: z.number().int().nonnegative(),
    terminal: SaveUiObservationSchema,
    violation: z.boolean(),
  })
  .strict()
  .readonly();
const DebounceBaseShape = {
  backendUnplaced: z.boolean(),
  foreignSuccess: SaveUiObservationSchema.nullable(),
  held: SaveUiObservationSchema,
  terminal: SaveUiObservationSchema,
  violation: z.boolean(),
} as const;
const DebounceUnplaceEvidenceSchema = z.discriminatedUnion(
  "layoutPatchStatus",
  [
    z
      .object({
        ...DebounceBaseShape,
        layoutPatchStatus: z.literal(200),
        recovery: z.null(),
      })
      .strict()
      .readonly(),
    z
      .object({
        ...DebounceBaseShape,
        layoutPatchStatus: z.literal(500),
        recovery: RecoveryObservationSchema,
      })
      .strict()
      .readonly(),
  ],
);
export class DriverEvidenceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DriverEvidenceError";
  }
}

export const REQUIRED_SAVE_STATE_SCENARIOS = [
  "safe-preflush-replacement",
  "queued-cancellation",
  "debounce-unplace-200",
  "debounce-unplace-500-recovery",
  "color-retry-title-success-200",
  "color-retry-title-success-500",
  "held-layout-color",
  "layout-foreign-failure",
  "title-foreign-failure",
  "stale-a-b-a-200",
  "stale-a-b-a-500",
] as const;

export type RequiredSaveStateScenario =
  (typeof REQUIRED_SAVE_STATE_SCENARIOS)[number];

export function assertRequiredScenarioCoverage(
  outcomes: Readonly<
    Partial<Record<RequiredSaveStateScenario, "passed" | "failed">>
  >,
): void {
  for (const scenario of REQUIRED_SAVE_STATE_SCENARIOS) {
    if (outcomes[scenario] !== "passed") {
      throw new DriverEvidenceError(
        `required save-state scenario did not pass: ${scenario}`,
      );
    }
  }
}

function assertObservation(
  observation: SaveUiObservation,
  expected: SaveUiObservation,
  message: string,
): void {
  if (
    observation.status !== expected.status ||
    observation.startDisabled !== expected.startDisabled
  ) {
    throw new DriverEvidenceError(message);
  }
}

function parseEvidence<T>(
  schema: z.ZodType<T>,
  evidence: unknown,
  label: string,
): T {
  const result = schema.safeParse(evidence);
  if (!result.success) {
    throw new DriverEvidenceError(`malformed ${label} evidence`);
  }
  return result.data;
}

function assertNeverStatus(status: never): never {
  throw new DriverEvidenceError(`unsupported layout PATCH status: ${status}`);
}

export function assertQueuedCancellationEvidence(
  untrustedEvidence: unknown,
): void {
  const evidence = parseEvidence(
    QueuedCancellationEvidenceSchema,
    untrustedEvidence,
    "queued cancellation",
  );
  if (evidence.violation) {
    throw new DriverEvidenceError("queued cancellation aggregate violation");
  }
  if (evidence.failureProvenanceLost) {
    throw new DriverEvidenceError(
      "queued cancellation failure provenance lost",
    );
  }
  if (
    evidence.patchStatusesBeforeRecovery.length !== 3 ||
    evidence.patchStatusesBeforeRecovery[0] !== 200 ||
    evidence.patchStatusesBeforeRecovery[1] !== 500 ||
    evidence.patchStatusesBeforeRecovery[2] !== 200
  ) {
    throw new DriverEvidenceError(
      "queued cancellation responses must be ordered B200, A1-500, A2-200",
    );
  }
  assertObservation(
    evidence.unresolved,
    { startDisabled: true, status: "저장 실패" },
    "queued cancellation requires visible failure and Start disabled while A2 is unresolved",
  );
  assertObservation(
    evidence.terminal,
    { startDisabled: false, status: "저장 실패" },
    "queued cancellation must retain terminal failure after A2 succeeds",
  );
  assertObservation(
    evidence.recovery,
    { startDisabled: false, status: "저장됨" },
    "queued cancellation A3 recovery must end saved with Start enabled",
  );
}

export function assertSafeReplacementEvidence(
  untrustedEvidence: unknown,
): void {
  const evidence = parseEvidence(
    SafeReplacementEvidenceSchema,
    untrustedEvidence,
    "safe replacement",
  );
  if (evidence.violation) {
    throw new DriverEvidenceError("safe replacement aggregate violation");
  }
  if (evidence.replacedAttemptPatchRequests !== 0) {
    throw new DriverEvidenceError(
      "safe replacement replaced A1 must not PATCH",
    );
  }
  if (evidence.phantomPending) {
    throw new DriverEvidenceError(
      "safe replacement must not leave phantom pending",
    );
  }
  assertObservation(
    evidence.terminal,
    { startDisabled: false, status: "저장됨" },
    "safe replacement must end saved with Start enabled",
  );
}

export function assertDebounceUnplaceEvidence(
  untrustedEvidence: unknown,
): void {
  const evidence = parseEvidence(
    DebounceUnplaceEvidenceSchema,
    untrustedEvidence,
    "debounce unplace",
  );
  if (evidence.violation) {
    throw new DriverEvidenceError("debounce unplace aggregate violation");
  }
  assertObservation(
    evidence.held,
    { startDisabled: true, status: "저장 중" },
    "debounce unplace held attempt requires saving UI and Start disabled",
  );
  switch (evidence.layoutPatchStatus) {
    case 200:
      if (
        evidence.terminal.status !== "저장됨" ||
        evidence.terminal.startDisabled ||
        !evidence.backendUnplaced
      ) {
        throw new DriverEvidenceError(
          "debounce unplace PATCH 200 requires terminal saved UI and backend read-back",
        );
      }
      return;
    case 500:
      assertObservation(
        evidence.terminal,
        { startDisabled: false, status: "저장 실패" },
        "debounce unplace PATCH 500 must retain terminal failure",
      );
      if (
        evidence.foreignSuccess !== null &&
        (evidence.foreignSuccess.status !== "저장 실패" ||
          evidence.foreignSuccess.startDisabled)
      ) {
        throw new DriverEvidenceError(
          "debounce unplace foreign success must not recover layout failure or remain unsettled",
        );
      }
      assertObservation(
        evidence.recovery,
        { startDisabled: false, status: "저장됨" },
        "debounce unplace recovery must end saved with Start enabled",
      );
      if (!evidence.backendUnplaced) {
        throw new DriverEvidenceError(
          "debounce unplace recovery requires backend roster read-back",
        );
      }
      return;
    default:
      assertNeverStatus(evidence);
  }
}
