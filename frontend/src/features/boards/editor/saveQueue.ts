export type SaveState = "idle" | "saving" | "saved" | "failed";
export const SAVE_DEBOUNCE_MS = 80;

type Save = () => Promise<void>;
type Recover = ((error: unknown) => Promise<void>) | undefined;
export type DebouncedSaveLifecycleEvent =
  | { readonly type: "flushed" }
  | { readonly type: "replaced" };
type ObserveLifecycle =
  | ((event: DebouncedSaveLifecycleEvent) => void)
  | undefined;
type Deferred = {
  readonly reject: (error: unknown) => void;
  readonly resolve: () => void;
};
type PendingSave = {
  readonly observeLifecycle: ObserveLifecycle;
  readonly recover: Recover;
  readonly save: Save;
  readonly waiters: readonly Deferred[];
};

export class SerializedSaveQueue {
  private activeCount = 0;
  private readonly listeners = new Set<(busy: boolean) => void>();
  private pending = new Map<string, PendingSave>();
  private tail: Promise<void> = Promise.resolve();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();

  enqueue(
    save: Save,
    recover?: (error: unknown) => Promise<void>,
  ): Promise<void> {
    this.setActiveCount(this.activeCount + 1);
    const result = this.tail.then(async () => {
      try {
        await save();
      } catch (error) {
        if (recover === undefined) throw error;
        await recover(error);
      }
    });
    this.tail = result
      .catch(() => undefined)
      .finally(() => this.setActiveCount(this.activeCount - 1));
    return result;
  }

  isBusy(): boolean {
    return this.activeCount > 0 || this.pending.size > 0;
  }

  subscribe(listener: (busy: boolean) => void): () => void {
    this.listeners.add(listener);
    listener(this.isBusy());
    return () => this.listeners.delete(listener);
  }

  enqueueDebounced(
    target: string,
    save: Save,
    recover?: (error: unknown) => Promise<void>,
    observeLifecycle?: (event: DebouncedSaveLifecycleEvent) => void,
  ): Promise<void> {
    const result = new Promise<void>((resolve, reject) => {
      const replaced = this.pending.get(target);
      const waiters = [...(replaced?.waiters ?? []), { reject, resolve }];
      if (replaced !== undefined)
        replaced.observeLifecycle?.({ type: "replaced" });
      this.pending.set(target, {
        observeLifecycle,
        recover,
        save,
        waiters,
      });
    });
    this.notify();
    const timer = this.timers.get(target);
    if (timer !== undefined) clearTimeout(timer);
    this.timers.set(
      target,
      setTimeout(() => {
        void this.flush(target);
      }, SAVE_DEBOUNCE_MS),
    );
    return result;
  }

  flush(target?: string): Promise<void> {
    if (target === undefined)
      return Promise.all(
        [...this.pending.keys()].map((key) => this.flush(key)),
      ).then(() => undefined);
    const timer = this.timers.get(target);
    if (timer !== undefined) clearTimeout(timer);
    this.timers.delete(target);
    const pending = this.pending.get(target);
    this.pending.delete(target);
    pending?.observeLifecycle?.({ type: "flushed" });
    this.notify();
    if (pending === undefined) return this.tail;
    const result = this.enqueue(pending.save, pending.recover);
    void result.then(
      () => {
        for (const waiter of pending.waiters) waiter.resolve();
      },
      (error: unknown) => {
        for (const waiter of pending.waiters) waiter.reject(error);
      },
    );
    return result;
  }

  private notify(): void {
    const busy = this.isBusy();
    for (const listener of this.listeners) listener(busy);
  }

  private setActiveCount(count: number): void {
    this.activeCount = count;
    this.notify();
  }
}
