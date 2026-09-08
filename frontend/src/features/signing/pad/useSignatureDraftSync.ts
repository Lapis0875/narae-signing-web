import { useCallback, useEffect, useRef, type RefObject } from "react";
import type {
  SignatureDraftDelta,
  SignatureDraftVersion,
} from "../api/signatureDraftApi.ts";
import type { SignaturePayload, SignaturePoint } from "./signaturePayload.ts";

type PendingDelta = {
  readonly operation: SignatureDraftDelta["operation"];
  readonly points: readonly SignaturePoint[];
  readonly strokeIndex: number;
};

type DraftSyncOptions = {
  readonly payload: () => SignaturePayload | null;
  readonly readyForFull: () => boolean;
  readonly sendDelta: ((
    delta: SignatureDraftDelta,
  ) => Promise<SignatureDraftVersion | null>) | undefined;
  readonly sendFull: ((
    payload: SignaturePayload,
  ) => Promise<SignatureDraftVersion | null>) | undefined;
  readonly setFailure: () => void;
  readonly stopping: RefObject<boolean>;
};

export function useSignatureDraftSync({
  payload,
  readyForFull,
  sendDelta,
  sendFull,
  setFailure,
  stopping,
}: DraftSyncOptions) {
  const pendingRef = useRef<PendingDelta[]>([]);
  const mountedRef = useRef(false);
  const requestRef = useRef<Promise<void> | null>(null);
  const sendingRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const recoveryRef = useRef(false);
  const fullSyncRef = useRef(false);
  const initialSyncRef = useRef(true);
  const initialPayloadRef = useRef<SignaturePayload>({ strokes: [], version: 1 });
  const sequenceRef = useRef(0);
  const versionRef = useRef<SignatureDraftVersion>({ draftEpoch: 0, revision: 0 });

  const flush = useCallback(() => {
    if (
      sendFull === undefined ||
      sendDelta === undefined ||
      sendingRef.current ||
      !mountedRef.current ||
      stopping.current
    ) {
      return;
    }
    const sendAuthoritative = initialSyncRef.current || recoveryRef.current || fullSyncRef.current;
    const next = pendingRef.current[0];
    if (!sendAuthoritative && next === undefined) {
      return;
    }
    if (sendAuthoritative && !initialSyncRef.current && !readyForFull()) {
      return;
    }
    sendingRef.current = true;
    if (sendAuthoritative) {
      const currentPayload = initialSyncRef.current
        ? initialPayloadRef.current
        : payload();
      const reflectedCount = initialSyncRef.current ? 0 : pendingRef.current.length;
      if (currentPayload === null) {
        setFailure();
        sendingRef.current = false;
        return;
      }
      const request = Promise.resolve()
        .then(() => sendFull(currentPayload))
        .then(
          (saved) => {
            if (saved === null) {
              setFailure();
              return;
            }
            pendingRef.current.splice(0, reflectedCount);
            versionRef.current = saved;
            sequenceRef.current = 0;
            initialSyncRef.current = false;
            recoveryRef.current = false;
            fullSyncRef.current = false;
          },
          () => setFailure(),
        )
        .then(() => {
          sendingRef.current = false;
          if (
            !initialSyncRef.current &&
            !recoveryRef.current &&
            !fullSyncRef.current &&
            mountedRef.current &&
            timerRef.current === null
          ) {
            queueMicrotask(flush);
          }
        });
      requestRef.current = request;
      void request;
      return;
    }
    if (next === undefined) {
      return;
    }
    const sequence = sequenceRef.current + 1;
    const delta: SignatureDraftDelta = {
      clientSequence: sequence,
      draftEpoch: versionRef.current.draftEpoch,
      operation: next.operation,
      points: next.points,
      revision: versionRef.current.revision,
      strokeIndex: next.strokeIndex,
    };
    const request = Promise.resolve()
      .then(() => sendDelta(delta))
      .then(
        (saved) => {
          if (saved === null) {
            recoveryRef.current = true;
            setFailure();
            return;
          }
          pendingRef.current.shift();
          sequenceRef.current = sequence;
          versionRef.current = saved;
        },
        () => {
          recoveryRef.current = true;
          setFailure();
        },
      )
      .then(() => {
        sendingRef.current = false;
        queueMicrotask(flush);
      });
    requestRef.current = request;
    void request;
  }, [payload, readyForFull, sendDelta, sendFull, setFailure, stopping]);

  const schedule = useCallback((delay = 50) => {
    if (sendFull === undefined || sendDelta === undefined) {
      return;
    }
    if (timerRef.current !== null) {
      if (delay > 0) {
        return;
      }
      clearTimeout(timerRef.current);
    }
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      flush();
    }, delay);
  }, [flush, sendDelta, sendFull]);

  const queue = useCallback((delta: PendingDelta, immediate = false) => {
    pendingRef.current.push(delta);
    schedule(immediate ? 0 : 50);
  }, [schedule]);

  const replace = useCallback(() => {
    fullSyncRef.current = true;
    flush();
  }, [flush]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (sendFull === undefined || sendDelta === undefined) {
      return;
    }
    flush();
    const heartbeat = setInterval(() => {
      fullSyncRef.current = true;
      flush();
    }, 20_000);
    return () => clearInterval(heartbeat);
  }, [flush, sendDelta, sendFull]);

  useEffect(() => () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
    }
  }, []);

  const stop = useCallback(async () => {
    stopping.current = true;
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    pendingRef.current = [];
    await requestRef.current;
  }, [stopping]);

  return { queue, replace, stop };
}
