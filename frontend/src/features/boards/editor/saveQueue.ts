export type SaveState = "idle" | "saving" | "saved" | "failed"
export const SAVE_DEBOUNCE_MS = 80

type Save = () => Promise<void>
type Recover = ((error: unknown) => Promise<void>) | undefined
type Deferred = { readonly reject: (error: unknown) => void; readonly resolve: () => void }
type PendingSave = { readonly recover: Recover; readonly save: Save; readonly waiters: readonly Deferred[] }

export class SerializedSaveQueue {
  private pending = new Map<string, PendingSave>()
  private tail: Promise<void> = Promise.resolve()
  private timers = new Map<string, ReturnType<typeof setTimeout>>()

  enqueue(save: Save, recover?: (error: unknown) => Promise<void>): Promise<void> {
    const result = this.tail.then(async () => {
      try {
        await save()
      } catch (error) {
        if (recover === undefined) throw error
        await recover(error)
      }
    })
    this.tail = result.catch(() => undefined)
    return result
  }

  enqueueDebounced(target: string, save: Save, recover?: (error: unknown) => Promise<void>): Promise<void> {
    const result = new Promise<void>((resolve, reject) => {
      const waiters = [...(this.pending.get(target)?.waiters ?? []), { reject, resolve }]
      this.pending.set(target, { recover, save, waiters })
    })
    const timer = this.timers.get(target)
    if (timer !== undefined) clearTimeout(timer)
    this.timers.set(target, setTimeout(() => { void this.flush(target) }, SAVE_DEBOUNCE_MS))
    return result
  }

  flush(target?: string): Promise<void> {
    if (target === undefined) return Promise.all([...this.pending.keys()].map((key) => this.flush(key))).then(() => undefined)
    const timer = this.timers.get(target)
    if (timer !== undefined) clearTimeout(timer)
    this.timers.delete(target)
    const pending = this.pending.get(target)
    this.pending.delete(target)
    if (pending === undefined) return this.tail
    const result = this.enqueue(pending.save, pending.recover)
    void result.then(
      () => { for (const waiter of pending.waiters) waiter.resolve() },
      (error: unknown) => { for (const waiter of pending.waiters) waiter.reject(error) },
    )
    return result
  }
}
