import { useState } from 'react'
import { uuid } from '@/lib/text'

/**
 * Idempotency keys (docs/build/S7-frontend.md section 3, S3 section 5).
 *
 * A key is created when the user first presses a command button and is reused for every retry of
 * the same command. A new key is created only when the user changes the input, which is expressed
 * by passing a different fingerprint.
 */
export class IdempotencyKeyStore {
  private current: { fingerprint: string; key: string } | null = null

  keyFor(fingerprint: string): string {
    if (this.current?.fingerprint !== fingerprint) {
      this.current = { fingerprint, key: uuid() }
    }
    return this.current.key
  }

  reset(): void {
    this.current = null
  }
}

/** One store per mounted command (form, dialog, panel). */
export function useIdempotencyKey(): IdempotencyKeyStore {
  const [store] = useState(() => new IdempotencyKeyStore())
  return store
}

/** Stable fingerprint of a command's inputs. */
export function fingerprintOf(...parts: unknown[]): string {
  return JSON.stringify(parts)
}
