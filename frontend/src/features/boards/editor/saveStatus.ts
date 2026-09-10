import type { SaveState } from "./saveQueue.ts";

export type SaveOwner =
  | { readonly kind: "color" }
  | { readonly kind: "title" }
  | { readonly kind: "layout"; readonly slotId: string }
  | { readonly kind: "background" }
  | { readonly kind: "transition" }
  | { readonly kind: "share-reissue" }
  | { readonly kind: "display-replacement" };

export type SaveOperation = {
  readonly attempt: number;
  readonly generation: number;
  readonly owner: SaveOwner;
};

type PendingAttempt = {
  readonly attempt: number;
  readonly recoversFailures: readonly number[];
  readonly writeFailed: boolean;
};

type OwnerStatus = {
  readonly failedAttempts: readonly number[];
  readonly pendingAttempts: readonly PendingAttempt[];
};

export type SaveStatusState = {
  readonly generation: number;
  readonly hasSettledOperation: boolean;
  readonly owners: Readonly<Record<string, OwnerStatus>>;
};

export type SaveStatusEvent =
  | { readonly type: "generation-changed"; readonly generation: number }
  | ({ readonly type: "operation-started" } & SaveOperation)
  | ({ readonly type: "operation-cancelled" } & SaveOperation)
  | ({ readonly type: "operation-succeeded" } & SaveOperation)
  | ({ readonly type: "operation-write-failed" } & SaveOperation)
  | ({ readonly type: "operation-recovery-finished" } & SaveOperation);

const emptyOwnerStatus: OwnerStatus = {
  failedAttempts: [],
  pendingAttempts: [],
};

export function createSaveStatusState(generation: number): SaveStatusState {
  return { generation, hasSettledOperation: false, owners: {} };
}

export function saveOwnerKey(owner: SaveOwner): string {
  switch (owner.kind) {
    case "color":
    case "title":
    case "background":
    case "transition":
    case "share-reissue":
    case "display-replacement":
      return owner.kind;
    case "layout":
      return `layout:${owner.slotId}`;
    default:
      return assertNever(owner);
  }
}

export function saveStatusReducer(
  state: SaveStatusState,
  event: SaveStatusEvent,
): SaveStatusState {
  switch (event.type) {
    case "generation-changed":
      return event.generation > state.generation
        ? createSaveStatusState(event.generation)
        : state;
    case "operation-started":
      return updateOwner(state, event, (current) => ({
        failedAttempts: current.failedAttempts,
        pendingAttempts: [
          ...current.pendingAttempts,
          {
            attempt: event.attempt,
            recoversFailures: current.failedAttempts,
            writeFailed: false,
          },
        ],
      }));
    case "operation-cancelled":
      return updateCurrentAttempt(state, event, (current) => ({
        failedAttempts: current.failedAttempts,
        pendingAttempts: withoutAttempt(current.pendingAttempts, event.attempt),
      }));
    case "operation-succeeded":
      return updateCurrentAttempt(
        state,
        event,
        (current, completed) => ({
          failedAttempts: current.failedAttempts.filter(
            (attempt) => !completed.recoversFailures.includes(attempt),
          ),
          pendingAttempts: withoutAttempt(
            current.pendingAttempts,
            event.attempt,
          ),
        }),
        true,
      );
    case "operation-write-failed":
      return updateCurrentAttempt(
        state,
        event,
        (current) => ({
          failedAttempts: current.failedAttempts.includes(event.attempt)
            ? current.failedAttempts
            : [...current.failedAttempts, event.attempt],
          pendingAttempts: current.pendingAttempts.map((pending) =>
            pending.attempt === event.attempt
              ? { ...pending, writeFailed: true }
              : pending,
          ),
        }),
        true,
      );
    case "operation-recovery-finished":
      return updateCurrentAttempt(state, event, (current) => ({
        failedAttempts: current.failedAttempts,
        pendingAttempts: withoutAttempt(current.pendingAttempts, event.attempt),
      }));
    default:
      return assertNever(event);
  }
}

export function selectSaveState(
  state: SaveStatusState,
  generation: number,
): SaveState {
  if (state.generation !== generation) return "idle";
  const statuses = Object.values(state.owners);
  const hasUncoveredFailure = statuses.some((status) =>
    status.failedAttempts.some(
      (failure) =>
        !status.pendingAttempts.some(
          (pending) =>
            !pending.writeFailed && pending.recoversFailures.includes(failure),
        ),
    ),
  );
  if (hasUncoveredFailure) return "failed";
  if (statuses.some((status) => status.pendingAttempts.length > 0))
    return "saving";
  return state.hasSettledOperation ? "saved" : "idle";
}

export function isSavePending(
  state: SaveStatusState,
  generation: number,
  owner?: SaveOwner,
): boolean {
  if (state.generation !== generation) return false;
  if (owner === undefined)
    return Object.values(state.owners).some(
      (status) => status.pendingAttempts.length > 0,
    );
  const status = state.owners[saveOwnerKey(owner)];
  return status !== undefined && status.pendingAttempts.length > 0;
}

function updateCurrentAttempt(
  state: SaveStatusState,
  operation: SaveOperation,
  update: (current: OwnerStatus, pending: PendingAttempt) => OwnerStatus,
  hasSettledOperation = state.hasSettledOperation,
): SaveStatusState {
  if (operation.generation !== state.generation) return state;
  const current = state.owners[saveOwnerKey(operation.owner)];
  const pending = current?.pendingAttempts.find(
    (candidate) => candidate.attempt === operation.attempt,
  );
  if (current === undefined || pending === undefined) return state;
  return updateOwner(
    state,
    operation,
    (owner) => update(owner, pending),
    hasSettledOperation,
  );
}

function withoutAttempt(
  attempts: readonly PendingAttempt[],
  completedAttempt: number,
): readonly PendingAttempt[] {
  return attempts.filter((attempt) => attempt.attempt !== completedAttempt);
}

function updateOwner(
  state: SaveStatusState,
  operation: SaveOperation,
  update: (current: OwnerStatus) => OwnerStatus,
  hasSettledOperation = state.hasSettledOperation,
): SaveStatusState {
  if (operation.generation !== state.generation) return state;
  const key = saveOwnerKey(operation.owner);
  return {
    generation: state.generation,
    hasSettledOperation,
    owners: {
      ...state.owners,
      [key]: update(state.owners[key] ?? emptyOwnerStatus),
    },
  };
}

function assertNever(value: never): never {
  throw new TypeError(`Unhandled save status variant: ${String(value)}`);
}
