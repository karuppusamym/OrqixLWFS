import type { AiDraftResult } from './api'

/**
 * Review notes of an AI-drafted workflow (assumptions, missing configuration, generation facts), handed from the
 * Describe-it flow to the studio. Kept in sessionStorage for this tab only (the server never stores model output),
 * with an in-memory fallback when storage is unavailable.
 */
export interface ReviewNotes {
  workflowId: string
  assumptions: string[]
  missingConfiguration: AiDraftResult['missingConfiguration']
  generation: AiDraftResult['generation']
}

const memory = new Map<string, ReviewNotes>()
const keyOf = (ws: string, workflowId: string) => `orqix.ai.review.${ws}.${workflowId}`

export function saveReviewNotes(ws: string, result: AiDraftResult): void {
  const notes: ReviewNotes = {
    workflowId: result.workflow.id,
    assumptions: result.assumptions,
    missingConfiguration: result.missingConfiguration,
    generation: result.generation,
  }
  const key = keyOf(ws, result.workflow.id)
  memory.set(key, notes)
  try {
    window.sessionStorage.setItem(key, JSON.stringify(notes))
  } catch {
    // convenience only: the in-memory copy still serves this page
  }
}

export function readReviewNotes(ws: string, workflowId: string): ReviewNotes | null {
  const key = keyOf(ws, workflowId)
  try {
    const raw = window.sessionStorage.getItem(key)
    if (raw) {
      const parsed = JSON.parse(raw) as ReviewNotes
      if (parsed && parsed.workflowId === workflowId && Array.isArray(parsed.assumptions) && Array.isArray(parsed.missingConfiguration)) return parsed
    }
  } catch {
    // fall through to memory
  }
  return memory.get(key) ?? null
}

export function clearReviewNotes(ws: string, workflowId: string): void {
  const key = keyOf(ws, workflowId)
  memory.delete(key)
  try {
    window.sessionStorage.removeItem(key)
  } catch {
    // ignore
  }
}
