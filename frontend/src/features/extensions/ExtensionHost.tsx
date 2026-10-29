import { useCallback, useEffect, useRef, useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { FRAME_ALLOW, PROTOCOL, parseFrameMessage, type FrameSource } from './frame'

export interface HostResult { completed: boolean; steps: Array<{ action: string; status: string; problemCode?: string | null }> }

export interface ExtensionHostProps {
  label: string
  /** Where the sandboxed document comes from; null while it is being resolved. */
  source: FrameSource | null
  /** Only server-filtered granted bindings; undefined while loading. */
  data: Record<string, unknown> | undefined
  /** Chains this component may request; anything else is refused by the host and by the server. */
  chains: Array<{ id: string; label: string }>
  height: number
  blocked?: string | null
  onRun: (chainId: string) => Promise<HostResult>
  readyTimeoutMs?: number
  heartbeatMs?: number
}

type State = { kind: 'loading' } | { kind: 'ready' } | { kind: 'failed'; reason: string }
const MAX_RUNS_PER_WINDOW = 5
const RUN_WINDOW_MS = 10_000

/**
 * Renders one extension in a sandboxed iframe (`allow-scripts` only, so it has an opaque origin and no access to this
 * page, its cookies or storage) and talks to it only through the bridge protocol. A crash, a start-up timeout, a missed
 * heartbeat or a navigation away tears the frame down and shows a fallback; the rest of the page keeps working. The
 * bridge reports that its document is unloading before a navigation leaves it, so the host stops at once and never
 * sends granted data to whatever document comes next (S114).
 */
export function ExtensionHost({ label, source, data, chains, height, blocked, onRun, readyTimeoutMs = 5000, heartbeatMs = 5000 }: ExtensionHostProps) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [frameHeight, setFrameHeight] = useState(height)
  const [attempt, setAttempt] = useState(0)
  const loads = useRef(0)
  const runs = useRef<number[]>([])
  const pendingPong = useRef<string | null>(null)
  const readied = useRef(false)
  const latest = useRef({ data, chains, onRun })
  useEffect(() => { latest.current = { data, chains, onRun } })

  const post = useCallback((message: Record<string, unknown>) => {
    // An opaque-origin frame cannot be addressed by origin; the message goes only to this frame's own window.
    frame.current?.contentWindow?.postMessage({ protocol: PROTOCOL, ...message }, '*')
  }, [])
  const fail = useCallback((reason: string) => setState(current => current.kind === 'failed' ? current : { kind: 'failed', reason }), [])

  useEffect(() => {
    if (blocked || !source || state.kind === 'failed') return
    const onMessage = (event: MessageEvent) => {
      if (!frame.current || event.source !== frame.current.contentWindow) return
      const message = parseFrameMessage(event.data)
      if (!message) return
      switch (message.type) {
        case 'ready':
          // A second ready means a new document started in the frame; the approved one is gone.
          if (readied.current) { fail('it navigated away from its approved document'); break }
          readied.current = true
          setState({ kind: 'ready' })
          post({ type: 'init', data: latest.current.data ?? {}, chains: latest.current.chains })
          break
        case 'resize':
          setFrameHeight(Math.min(800, Math.max(80, Math.round(message.height))))
          break
        case 'error':
          fail(`it stopped with an error (${message.message})`)
          break
        case 'unloading':
          fail('it navigated away from its approved document')
          break
        case 'pong':
          if (message.nonce === pendingPong.current) pendingPong.current = null
          break
        case 'action': {
          const reply = (result: unknown) => post({ type: 'result', requestId: message.requestId, result })
          if (!latest.current.chains.some(chain => chain.id === message.chainId)) { reply({ completed: false, error: 'This action is not granted to the extension.' }); break }
          const now = Date.now()
          runs.current = runs.current.filter(at => now - at < RUN_WINDOW_MS)
          if (runs.current.length >= MAX_RUNS_PER_WINDOW) { reply({ completed: false, error: 'Too many actions. Wait a moment.' }); break }
          runs.current.push(now)
          void latest.current.onRun(message.chainId).then(
            result => reply({ completed: result.completed, steps: result.steps.map(step => ({ action: step.action, status: step.status, problemCode: step.problemCode ?? null })) }),
            () => reply({ completed: false, error: 'The action could not be run.' }),
          )
          break
        }
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [blocked, source, state.kind, post, fail, attempt])

  useEffect(() => {
    if (blocked || !source || state.kind !== 'loading') return
    const timer = window.setTimeout(() => fail('it did not start in time'), readyTimeoutMs)
    return () => window.clearTimeout(timer)
  }, [blocked, source, state.kind, readyTimeoutMs, fail, attempt])

  useEffect(() => {
    if (state.kind !== 'ready') return
    const timer = window.setInterval(() => {
      if (pendingPong.current) { fail('it stopped responding'); return }
      pendingPong.current = Math.random().toString(36).slice(2)
      post({ type: 'ping', nonce: pendingPong.current })
    }, heartbeatMs)
    return () => window.clearInterval(timer)
  }, [state.kind, heartbeatMs, post, fail])

  // Post only when the granted content changes, not on every host render.
  const payload = JSON.stringify({ data: data ?? {}, chains })
  useEffect(() => {
    if (state.kind === 'ready') post({ type: 'data', ...JSON.parse(payload) as Record<string, unknown> })
  }, [payload, state.kind, post])

  const retry = () => {
    loads.current = 0
    readied.current = false
    pendingPong.current = null
    runs.current = []
    setState({ kind: 'loading' })
    setAttempt(value => value + 1)
  }

  if (blocked || state.kind === 'failed') {
    return <div role="status" className="flex flex-wrap items-start gap-3 rounded-card border border-dashed border-border bg-surface-muted p-4 text-sm" data-testid="extension-fallback">
      <ShieldAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <p className="min-w-0 flex-1">{label} is unavailable: {blocked ?? (state.kind === 'failed' ? state.reason : '')}. The rest of the page keeps working.</p>
      {!blocked ? <Button size="sm" variant="secondary" onClick={retry}>Retry {label}</Button> : null}
    </div>
  }
  if (!source) return <p className="text-sm text-muted-foreground" role="status">Loading {label}</p>
  return <div className="space-y-1">
    {state.kind === 'loading' ? <p className="text-xs text-muted-foreground" role="status">Starting {label}</p> : null}
    <iframe
      key={attempt}
      ref={frame}
      title={label}
      sandbox="allow-scripts"
      allow={FRAME_ALLOW}
      referrerPolicy="no-referrer"
      className="w-full rounded-card border border-border bg-white"
      style={{ height: frameHeight }}
      src={source.src}
      srcDoc={source.srcdoc}
      onLoad={() => {
        loads.current += 1
        // A sandboxed frame may navigate itself; a second load means it left the approved document.
        if (loads.current > 1) fail('it navigated away from its approved document')
      }}
    />
  </div>
}
