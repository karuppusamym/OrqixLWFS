import { AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, CircleDot, XCircle } from 'lucide-react'
import { useEffect, useRef, type KeyboardEvent } from 'react'
import type { SimulationResult, SimulationStep } from '@/api/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'
import { describeProblem } from '@/lib/jsonSchemaLite'

type Obj = Record<string, unknown>
const asObj = (v: unknown): Obj => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : {})
const asArr = (v: unknown): unknown[] => (Array.isArray(v) ? v : [])
const show = (v: unknown): string => (v === null || v === undefined ? '—' : typeof v === 'boolean' ? (v ? 'yes' : 'no') : typeof v === 'object' ? JSON.stringify(v) : String(v))

/** One line per value: "procurementId: sample-id". */
function Facts({ title, values }: { title: string; values: Obj }) {
  const entries = Object.entries(values)
  if (entries.length === 0) return null
  return (
    <div>
      <p className="font-semibold">{title}</p>
      <ul className="mt-1 list-disc pl-5">
        {entries.map(([k, v]) => (
          <li key={k}>
            {k}: {show(v)}
          </li>
        ))}
      </ul>
    </div>
  )
}

function MatchMark({ matched }: { matched: unknown }) {
  if (matched === true) return <span className="font-semibold text-success-foreground">applies</span>
  if (matched === false) return <span className="text-muted-foreground">does not apply</span>
  return <span className="text-muted-foreground">not needed</span>
}

/** What the step looked at and decided, in business words. The sentence above it already says why. */
export function StepDetails({ entry }: { entry: SimulationStep }) {
  const d = asObj(entry.details)
  const attention = asObj(d.attention)
  const blocks: React.ReactNode[] = []
  switch (entry.kind) {
    case 'decision': {
      blocks.push(
        <div key="rows">
          <p className="font-semibold">Rules, in order</p>
          <ol className="mt-1 space-y-1">
            {asArr(d.rows).map((r) => {
              const row = asObj(r)
              return (
                <li key={String(row.row)} className={cn('rounded border px-2 py-1', row.selected === true ? 'border-primary bg-primary/5' : 'border-border')}>
                  Rule {show(row.row)}: {show(row.condition)} — <MatchMark matched={row.matched} />
                  {row.selected === true ? <span className="font-semibold"> (used)</span> : null}
                </li>
              )
            })}
            <li className={cn('rounded border px-2 py-1', d.usedDefault === true ? 'border-primary bg-primary/5' : 'border-border')}>Otherwise{d.usedDefault === true ? <span className="font-semibold"> (used)</span> : null}</li>
          </ol>
        </div>,
        <Facts key="in" title="Values looked at" values={Object.fromEntries(asArr(d.inputs).map((i) => [show(asObj(i).label), asObj(i).shown]))} />,
      )
      break
    }
    case 'branch':
      blocks.push(
        <div key="routes">
          <p className="font-semibold">Routes, in order</p>
          <ul className="mt-1 space-y-1">
            {asArr(d.routes).map((r) => {
              const route = asObj(r)
              return (
                <li key={show(route.transitionId)} className={cn('rounded border px-2 py-1', d.chosen === route.transitionId ? 'border-primary bg-primary/5' : 'border-border')}>
                  {show(route.label)}: <MatchMark matched={route.matched} />
                  {asArr(route.terms).length > 0 ? (
                    <ul className="list-disc pl-5">
                      {asArr(route.terms).map((t, i) => (
                        <li key={i}>
                          {show(asObj(t).text)} — <MatchMark matched={asObj(t).matched} />
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </li>
              )
            })}
          </ul>
        </div>,
      )
      break
    case 'approval':
      blocks.push(
        <p key="who">
          Assigned to: <span className="font-semibold">{show(d.groupName)}</span>
          {d.dueAfterHours ? ` (due after ${show(d.dueAfterHours)} hours)` : ''}
        </p>,
      )
      break
    case 'http':
      if (d.mocked === true) {
        blocks.push(
          <p key="mock" data-testid="mocked-answer">
            Mocked answer{typeof d.httpStatus === 'number' ? `: HTTP ${d.httpStatus}` : ''}
            {typeof d.delaySeconds === 'number' ? `, after ${d.delaySeconds} seconds` : ''}. Nothing was sent.
          </p>,
        )
      }
      if (asArr(d.schemaProblems).length > 0) {
        blocks.push(
          <div key="schema">
            <p className="font-semibold">Does not match the response schema</p>
            <ul className="mt-1 list-disc pl-5">
              {asArr(d.schemaProblems).map((p, i) => (
                <li key={i}>{describeProblem({ at: String(asObj(p).at ?? ''), keyword: String(asObj(p).keyword ?? '') })}</li>
              ))}
            </ul>
          </div>,
        )
      }
      blocks.push(<Facts key="req" title="Sent to the system" values={asObj(d.request)} />, <Facts key="res" title={d.sampleAnswer === true ? 'Kept from the sample answer' : 'Kept from the answer'} values={asObj(d.result)} />)
      break
    case 'externalTask':
    case 'sendAdapter':
      blocks.push(<Facts key="res" title={d.mocked === true ? 'Mocked result' : 'Result'} values={asObj(d.result)} />)
      break
    case 'waitForMessage':
      blocks.push(<Facts key="payload" title={d.mocked === true ? 'Mocked message' : 'Message'} values={asObj(d.payload)} />)
      break
    case 'notification':
      blocks.push(
        <p key="msg">
          Message: <span className="font-semibold">{show(d.message)}</span>
        </p>,
      )
      break
    case 'startWorkflow':
      blocks.push(
        <div key="occ">
          <p className="font-semibold">{show(d.workflowName)}</p>
          <ul className="mt-1 list-disc pl-5">
            {asArr(d.occurrences).map((o) => {
              const occ = asObj(o)
              return (
                <li key={show(occ.occurrence)}>
                  Run {show(occ.occurrence)}: {show(occ.outcome)}
                  {Array.isArray(occ.trace) ? ` (${occ.trace.length} steps tested)` : ''}
                </li>
              )
            })}
          </ul>
        </div>,
      )
      break
    default:
      break
  }
  if (Object.keys(attention).length > 0) {
    blocks.push(
      <p key="attention" className="font-semibold text-warning-foreground">
        Needs attention: {show(attention.message)}
      </p>,
    )
  }
  if (entry.wrote && Object.keys(entry.wrote).length > 0 && !['decision', 'http', 'externalTask', 'sendAdapter', 'waitForMessage'].includes(entry.kind)) {
    blocks.push(<Facts key="wrote" title="Saved for later steps" values={asObj(entry.wrote)} />)
  }
  if (blocks.length === 0) return null
  return <div className="mt-2 space-y-2 text-sm">{blocks}</div>
}

/**
 * Step-by-step playback of a test run: the sentence for the step, what it looked at, and the whole run as a text list
 * (the keyboard and screen reader alternative to the canvas). Arrow keys, Home and End move between steps while the
 * playback has focus; the current step is announced in a polite live region.
 */
export function Playback({ result, index, onIndex }: { result: SimulationResult; index: number; onIndex: (index: number) => void }) {
  const trace = result.trace
  const last = trace.length - 1
  const entry = trace[Math.min(Math.max(index, 0), last)] as SimulationStep
  const outcome = result.outcome
  const failed = outcome.status === 'finished' && outcome.outcomeKind === 'failed'
  const rejected = outcome.status === 'finished' && (outcome.outcomeKind === 'rejected' || failed)
  const attention = outcome.status === 'needsAttention'
  const Icon = attention ? AlertTriangle : rejected ? XCircle : CheckCircle2
  const currentRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    currentRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [index])

  const move = (to: number) => onIndex(Math.min(Math.max(to, 0), last))
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, number> = { ArrowRight: index + 1, ArrowDown: index + 1, ArrowLeft: index - 1, ArrowUp: index - 1, Home: 0, End: last }
    const target = keys[event.key]
    if (target === undefined) return
    event.preventDefault()
    move(target)
  }

  return (
    <section aria-label="Test run" data-testid="playback">
      <div className={cn('flex gap-2 rounded-card border-2 p-3', attention ? 'border-warning-foreground bg-warning-soft' : rejected ? 'border-outcome-rejected-border bg-status-rejected-bg' : 'border-outcome-success-border bg-status-success-bg')} data-testid="test-outcome">
        <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <div>
          <p className="font-semibold">{attention ? 'Needs attention' : failed ? `Failed · ${outcome.businessOutcome}` : outcome.businessOutcome}</p>
          <p className="text-sm">{outcome.summary}</p>
        </div>
      </div>

      <div className="mt-3 rounded-card border border-border p-3" data-testid="playback-steps">
        <div className="flex items-center justify-between gap-2">
          <p className="text-sm font-semibold">
            Step {index + 1} of {trace.length}
          </p>
          <div role="toolbar" aria-label="Step by step. Use the arrow keys to go to the previous or next step." onKeyDown={onKeyDown} className="flex gap-1">
            <Button variant="secondary" size="sm" onClick={() => move(index - 1)} aria-disabled={index <= 0} className="aria-disabled:opacity-60">
              <ChevronLeft aria-hidden="true" /> Previous step
            </Button>
            <Button variant="secondary" size="sm" onClick={() => move(index + 1)} aria-disabled={index >= last} className="aria-disabled:opacity-60">
              Next step <ChevronRight aria-hidden="true" />
            </Button>
          </div>
        </div>
        <div className="mt-2" data-testid="current-step">
          <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            {entry.kindLabel}
            {entry.branch ? ` · parallel branch ${entry.branch}` : ''}
          </p>
          <p className="text-base font-semibold">{entry.stepLabel}</p>
          <p className="mt-1">{entry.summary}</p>
          <StepDetails entry={entry} />
        </div>
        <p role="status" aria-live="polite" className="sr-only" data-testid="playback-live">
          Step {index + 1} of {trace.length}: {entry.summary}
        </p>
      </div>

      <details className="mt-3" open>
        <summary className="cursor-pointer text-sm font-semibold">All steps as a list</summary>
        <ol className="mt-2 space-y-1" aria-label="Steps of the test run">
          {trace.map((e, i) => (
            <li key={e.seq}>
              <button
                type="button"
                ref={i === index ? currentRef : undefined}
                onClick={() => onIndex(i)}
                aria-current={i === index ? 'step' : undefined}
                className={cn('flex w-full items-start gap-2 rounded px-2 py-1 text-left text-sm hover:bg-neutral-soft', i === index && 'bg-primary/10 font-semibold', i > index && 'text-muted-foreground')}
              >
                <CircleDot className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span>
                  <span className="sr-only">Step {i + 1}: </span>
                  {e.branch ? <span className="text-muted-foreground">[branch {e.branch}] </span> : null}
                  {e.summary}
                </span>
              </button>
            </li>
          ))}
        </ol>
      </details>
    </section>
  )
}
