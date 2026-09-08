import { cleanup, fireEvent, screen } from "@testing-library/react";
import { vi } from "vitest";
import type {
  SignatureDraftDelta,
  SignatureDraftVersion,
} from "../api/signatureDraftApi.ts";
import type { SignaturePayload, SignaturePoint } from "./signaturePayload.ts";

type Deferred<T> = {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
};

class UnexpectedDraftOperationError extends Error {
  constructor(operation: never) {
    super(`Unexpected draft operation: ${operation}`);
    this.name = "UnexpectedDraftOperationError";
  }
}

function assertNever(operation: never): never {
  throw new UnexpectedDraftOperationError(operation);
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => {};
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
}

export function version(
  draftEpoch: number,
  revision: number,
): SignatureDraftVersion {
  return { draftEpoch, revision };
}

export class ColdDraftContract {
  readonly operations: SignatureDraftDelta["operation"][] = [];
  readonly strokes: SignaturePoint[][] = [];
  private clientSequence = 0;
  private draftEpoch = 0;
  private openStroke = -1;
  private revision = 0;

  clear = (): Promise<boolean> => {
    this.strokes.splice(0);
    this.draftEpoch += 1;
    this.clientSequence = 0;
    this.openStroke = -1;
    this.revision = 0;
    return Promise.resolve(true);
  };

  put = (payload: SignaturePayload): Promise<SignatureDraftVersion | null> => {
    this.strokes.splice(
      0,
      this.strokes.length,
      ...payload.strokes.map((stroke) => [...stroke.points]),
    );
    this.draftEpoch += 1;
    this.clientSequence = 0;
    this.openStroke = -1;
    this.revision = 0;
    return Promise.resolve(version(this.draftEpoch, this.revision));
  };

  delta = (
    delta: SignatureDraftDelta,
  ): Promise<SignatureDraftVersion | null> => {
    if (
      this.draftEpoch === 0 ||
      delta.clientSequence !== this.clientSequence + 1 ||
      delta.draftEpoch !== this.draftEpoch ||
      delta.revision !== this.revision
    ) {
      return Promise.resolve(null);
    }
    switch (delta.operation) {
      case "begin": {
        if (
          this.openStroke >= 0 ||
          delta.strokeIndex !== this.strokes.length ||
          delta.points.length === 0
        ) {
          return Promise.resolve(null);
        }
        this.strokes.push([...delta.points]);
        this.openStroke = delta.strokeIndex;
        break;
      }
      case "append": {
        const stroke = this.strokes[delta.strokeIndex];
        if (
          this.openStroke !== delta.strokeIndex ||
          stroke === undefined ||
          delta.points.length === 0
        ) {
          return Promise.resolve(null);
        }
        stroke.push(...delta.points);
        break;
      }
      case "end":
        if (this.openStroke !== delta.strokeIndex || delta.points.length !== 0) {
          return Promise.resolve(null);
        }
        this.openStroke = -1;
        break;
      default:
        assertNever(delta.operation);
    }
    this.clientSequence = delta.clientSequence;
    this.operations.push(delta.operation);
    this.revision += 1;
    return Promise.resolve(version(this.draftEpoch, this.revision));
  };
}

export function prepareCanvas(): HTMLElement {
  const canvas = screen.getByTestId("signer-canvas");
  Object.defineProperties(canvas, {
    hasPointerCapture: { configurable: true, value: () => false },
    setPointerCapture: { configurable: true, value: () => undefined },
  });
  vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue(
    new DOMRect(0, 0, 100, 100),
  );
  return canvas;
}

export function setupSignaturePadTestEnvironment(): void {
  vi.useFakeTimers();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
}

export function teardownSignaturePadTestEnvironment(): void {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
}

export function dispatchPointer(
  canvas: HTMLElement,
  type: "pointerdown" | "pointermove" | "pointerup",
  clientX: number,
  clientY: number,
  coalesced: readonly {
    readonly clientX: number;
    readonly clientY: number;
  }[] = [],
): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    button: { value: 0 },
    clientX: { value: clientX },
    clientY: { value: clientY },
    getCoalescedEvents: { value: () => coalesced },
    isPrimary: { value: true },
    pointerId: { value: 1 },
  });
  fireEvent(canvas, event);
}
