import type { RunDetail, RunEvent } from '@/api/types'

/** Signal names (workflow schema signalName): kebab-case or camelCase, 2 to 64 characters. */
const SIGNAL_PATTERN = /^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$/

export function isSignalName(value: string): boolean {
  return value.length >= 2 && value.length <= 64 && SIGNAL_PATTERN.test(value)
}

export interface SignalChoice {
  signal: string
  /** Who receives it, in words: the waiting steps and the handlers it starts. */
  receivers: string[]
}

interface DefinitionShape {
  nodes?: Array<{ id?: unknown; type?: unknown; config?: { signal?: unknown } }>
  subprocesses?: Array<{ label?: unknown; type?: unknown; trigger?: { type?: unknown; signal?: unknown } }>
}

const text = (value: unknown): string | undefined => (typeof value === 'string' && value ? value : undefined)

/**
 * Signals an operator can send to this request (S59): the "Wait for a signal" steps that are open now and the signal
 * handlers of the released workflow. The step's signal name comes from the released definition, or from its
 * SIGNAL_WAIT_STARTED event when the caller cannot read the workflow. The server still decides: a signal nothing
 * waits for is refused with SIGNAL_NOT_AWAITED.
 */
export function signalChoices(run: Pick<RunDetail, 'currentSteps'>, events: RunEvent[], definition?: unknown): SignalChoice[] {
  const def = (definition ?? {}) as DefinitionShape
  const choices = new Map<string, string[]>()
  const add = (signal: string | undefined, receiver: string) => {
    if (!signal || !isSignalName(signal)) return
    const receivers = choices.get(signal) ?? []
    if (!receivers.includes(receiver)) receivers.push(receiver)
    choices.set(signal, receivers)
  }
  for (const step of run.currentSteps) {
    if (step.kind !== 'waitForSignal') continue
    const node = def.nodes?.find((n) => n.id === step.nodeId)
    const fromEvent = [...events].reverse().find((e) => e.type === 'SIGNAL_WAIT_STARTED' && e.nodeId === step.nodeId)
    add(text(node?.config?.signal) ?? text(fromEvent?.data.signal), `${step.label} (waiting now)`)
  }
  for (const scope of def.subprocesses ?? []) {
    if (scope.type !== 'event' || scope.trigger?.type !== 'signal') continue
    add(text(scope.trigger.signal), `the handler ${text(scope.label) ?? 'for this signal'}`)
  }
  return [...choices].map(([signal, receivers]) => ({ signal, receivers }))
}
