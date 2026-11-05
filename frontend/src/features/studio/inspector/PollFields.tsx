import { useMemo } from 'react'
import { FormField } from '@/components/common/FormField'
import { Alert } from '@/components/ui/alert'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useStudio, useStudioContext } from '../StudioContext'
import type { FieldInfo } from '../model/fieldRefs'
import { describe as describeSeconds, scheduleOf, seconds, summary, worstCase, CHECK_LIMIT, type PollConfig } from '../model/pollSchedule'
import { cfg, type Condition, type WorkflowNode } from '../model/types'
import { ConditionEditor } from './ConditionEditor'
import { CommitInput, CommitNumber, InspectorSection } from './controls'

/** Presets for the interval and the deadline; any ISO-8601 duration can be typed as well. */
const EVERY = ['PT30S', 'PT1M', 'PT5M', 'PT15M', 'PT1H', 'PT6H', 'P1D']
const GIVE_UP = ['PT1H', 'PT6H', 'P1D', 'P2D', 'P5D', 'P14D', 'P30D', 'P90D']

/** A typical number of waiting requests for the load sentence of the summary. */
const SAMPLE_WAITING = 500

/**
 * "Keep checking until..." on a Call a system step (docs/build/S129, CG-W01). Only for read-only operations: a check may
 * run many times. Orqix calls the operation, maps the answer, and continues when the "done" condition holds, takes the
 * failed path when the "failed" condition holds, or the timed-out path at the deadline. The computed summary is the
 * same as the compiler's, so the designer sees the worst-case load before publishing.
 */
export function PollFields({ node, readOnlyOperation, results, before, systemName }: {
  node: WorkflowNode
  /** True when the chosen operation only reads; null while unknown. */
  readOnlyOperation: boolean | null
  /** The kept result fields of this step, as condition fields. */
  results: FieldInfo[]
  /** Fields available before this step. */
  before: FieldInfo[]
  systemName: string
}) {
  const { store, readOnly } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const poll = cfg<PollConfig>(node, 'poll')
  const on = poll !== undefined
  const set = (next: PollConfig | undefined) => store.getState().updateNodeConfig(node.id, { poll: next })
  const patch = (p: Partial<PollConfig>) => {
    const next: Record<string, unknown> = { ...poll, ...p }
    for (const [k, v] of Object.entries(next)) if (v === undefined) delete next[k]
    set(next as PollConfig)
  }
  const fields = useMemo(() => [...results, ...before.filter((f) => f.type !== 'array' && f.type !== 'date')], [results, before])
  const schedule = scheduleOf(poll)
  const worst = worstCase(poll)
  const routes = def.transitions.filter((t) => t.from === node.id)
  const hasTimedOut = routes.some((t) => t.on === 'timedOut')
  const hasFailed = routes.some((t) => t.on === 'failed')

  const turnOn = () => {
    const first = results[0]
    set({
      every: 'PT15M',
      giveUpAfter: 'P1D',
      jitterPercent: 10,
      until: first ? { fieldRef: first.ref, operator: 'eq', value: first.enumValues?.[0] ?? '' } : { fieldRef: '', operator: 'eq', value: '' },
    })
  }

  return (
    <InspectorSection title="Keep checking until…">
      <div className="flex items-start gap-3">
        <Checkbox
          id={`poll-on-${node.id}`}
          data-field="config.poll"
          checked={on}
          disabled={readOnly || (!on && readOnlyOperation !== true)}
          onCheckedChange={(v) => (v === true ? turnOn() : set(undefined))}
        />
        <div>
          <Label htmlFor={`poll-on-${node.id}`} className="font-medium">Keep checking until the work is done</Label>
          <p className="text-xs text-muted-foreground">
            For a system that has no callback: Orqix checks again and again, and the request continues when the answer says the work is done.
          </p>
        </div>
      </div>
      {!on && readOnlyOperation === false ? (
        <p className="text-xs text-muted-foreground" data-testid="poll-needs-readonly">Only an operation that just reads can be checked repeatedly. This one changes something in the other system.</p>
      ) : null}
      {on ? (
        <div className="space-y-4" data-testid="poll-settings">
          <FormField label="Done when" required id={`poll-until-${node.id}`} description="Usually a status field of the answer, for example state is resolved.">
            {() => (
              <ConditionEditor
                idBase={`poll-until-${node.id}`}
                dataFieldBase="config.poll.until"
                condition={(poll.until ?? { fieldRef: '', operator: 'eq', value: '' }) as Condition}
                fields={fields}
                allowSets
                disabled={readOnly}
                onChange={(until) => patch({ until })}
              />
            )}
          </FormField>
          <div className="flex items-start gap-3">
            <Checkbox
              id={`poll-fail-on-${node.id}`}
              checked={poll.failWhen !== undefined}
              disabled={readOnly}
              onCheckedChange={(v) => patch({ failWhen: v === true ? { fieldRef: results[0]?.ref ?? '', operator: 'eq', value: '' } : undefined })}
            />
            <Label htmlFor={`poll-fail-on-${node.id}`}>Stop when the work failed for good</Label>
          </div>
          {poll.failWhen !== undefined ? (
            <ConditionEditor
              idBase={`poll-fail-${node.id}`}
              dataFieldBase="config.poll.failWhen"
              condition={poll.failWhen as Condition}
              fields={fields}
              allowSets
              disabled={readOnly}
              onChange={(failWhen) => patch({ failWhen })}
            />
          ) : null}

          <DurationChoice id={`poll-every-${node.id}`} label="Check every" field="config.poll.every" value={poll.every ?? ''} presets={EVERY} disabled={readOnly} onChange={(every) => patch({ every })} />
          <div className="flex items-start gap-3">
            <Checkbox
              id={`poll-backoff-${node.id}`}
              checked={poll.backoff !== undefined}
              disabled={readOnly}
              onCheckedChange={(v) => patch({ backoff: v === true ? { multiplier: 2, maxInterval: 'PT1H' } : undefined })}
            />
            <Label htmlFor={`poll-backoff-${node.id}`}>Check less often as time goes on</Label>
          </div>
          {poll.backoff ? (
            <div className="grid grid-cols-2 gap-3">
              <FormField label="Each wait longer by" id={`poll-mult-${node.id}`} description="1 to 4 times">
                {(ids) => (
                  <CommitNumber id={ids.input} data-field="config.poll.backoff.multiplier" value={poll.backoff?.multiplier} disabled={readOnly}
                    onCommit={(v) => patch({ backoff: { maxInterval: poll.backoff?.maxInterval ?? 'PT1H', multiplier: Math.max(1, Math.min(4, v ?? 2)) } })} />
                )}
              </FormField>
              <DurationChoice id={`poll-cap-${node.id}`} label="But at most every" field="config.poll.backoff.maxInterval" value={poll.backoff.maxInterval} presets={EVERY} disabled={readOnly}
                onChange={(maxInterval) => patch({ backoff: { multiplier: poll.backoff?.multiplier ?? 2, maxInterval } })} />
            </div>
          ) : null}
          <DurationChoice id={`poll-deadline-${node.id}`} label="Give up after" field="config.poll.giveUpAfter" value={poll.giveUpAfter ?? ''} presets={GIVE_UP} disabled={readOnly} onChange={(giveUpAfter) => patch({ giveUpAfter })} />

          {results.length > 0 ? (
            <fieldset className="space-y-1.5">
              <legend className="text-sm font-semibold">Show on the request page while waiting</legend>
              {results.map((f) => {
                const key = f.ref.split('/').pop() as string
                const shown = poll.showWhileWaiting ?? []
                return (
                  <div key={f.ref} className="flex items-center gap-2">
                    <Checkbox id={`poll-show-${node.id}-${key}`} checked={shown.includes(key)} disabled={readOnly || (!shown.includes(key) && shown.length >= 3)}
                      onCheckedChange={(v) => patch({ showWhileWaiting: v === true ? [...shown, key] : shown.filter((k) => k !== key).length ? shown.filter((k) => k !== key) : undefined })} />
                    <Label htmlFor={`poll-show-${node.id}-${key}`} className="font-normal">{f.label}</Label>
                  </div>
                )
              })}
            </fieldset>
          ) : null}

          <FormField label="After failing checks in a row" id={`poll-errors-${node.id}`} description="Timeouts, server errors and rate limits count; 1 to 20.">
            {(ids) => (
              <div className="flex flex-wrap items-center gap-2">
                <CommitNumber id={ids.input} integer className="w-20" data-field="config.poll.onCheckError.maxConsecutive" value={poll.onCheckError?.maxConsecutive ?? 5} disabled={readOnly}
                  onCommit={(v) => patch({ onCheckError: { maxConsecutive: Math.max(1, Math.min(20, v ?? 5)), ...(poll.onCheckError?.then ? { then: poll.onCheckError.then } : {}) } })} />
                <Select aria-label="Then" value={poll.onCheckError?.then ?? 'needsAttention'} disabled={readOnly}
                  onChange={(e) => patch({ onCheckError: { maxConsecutive: poll.onCheckError?.maxConsecutive ?? 5, then: e.target.value as 'needsAttention' | 'timedOut' } })}>
                  <option value="needsAttention">an operator must look at it</option>
                  <option value="timedOut">take the timed-out path</option>
                </Select>
              </div>
            )}
          </FormField>
          <div className="flex items-center gap-2">
            <Checkbox id={`poll-final-${node.id}`} checked={poll.finalCheckAtDeadline !== false} disabled={readOnly}
              onCheckedChange={(v) => patch({ finalCheckAtDeadline: v === true ? undefined : false })} />
            <Label htmlFor={`poll-final-${node.id}`} className="font-normal">One last check exactly at the deadline</Label>
          </div>

          <div role="status" data-testid="poll-summary" className="rounded-control border border-border bg-surface-muted p-3 text-sm">
            {schedule ? summary(schedule, systemName || 'the other system', SAMPLE_WAITING)
              : 'Choose an interval from 10 seconds to 1 day and a deadline from 1 minute to 90 days (at least the interval).'}
            {schedule && poll.jitterPercent !== 0 ? ` Checks are spread by up to ${poll.jitterPercent ?? 10} percent so they do not all happen at the same second.` : ''}
          </div>
          {worst !== null && worst > CHECK_LIMIT && !poll.maxChecks ? (
            <Alert tone="warning">These settings could check more than {CHECK_LIMIT} times. Check less often, give up sooner, or limit the number of checks.</Alert>
          ) : null}
          <FormField label="Most checks to make" id={`poll-max-${node.id}`} description={`Optional; at most ${CHECK_LIMIT}.`}>
            {(ids) => (
              <CommitNumber id={ids.input} integer data-field="config.poll.maxChecks" value={poll.maxChecks} disabled={readOnly}
                onCommit={(v) => patch({ maxChecks: v === undefined ? undefined : Math.max(1, Math.min(CHECK_LIMIT, v)) })} />
            )}
          </FormField>
          {!hasTimedOut || (poll.failWhen !== undefined && !hasFailed) ? (
            <Alert tone="info" data-testid="poll-routes">
              Draw {!hasTimedOut ? 'a path for when the checks give up' : ''}{!hasTimedOut && poll.failWhen !== undefined && !hasFailed ? ' and ' : ''}{poll.failWhen !== undefined && !hasFailed ? 'a path for when the work failed' : ''} from this step on the canvas.
            </Alert>
          ) : null}
        </div>
      ) : null}
    </InspectorSection>
  )
}

function DurationChoice({ id, label, field, value, presets, disabled, onChange }: {
  id: string; label: string; field: string; value: string; presets: string[]; disabled: boolean; onChange: (iso: string) => void
}) {
  const custom = value !== '' && !presets.includes(value)
  return (
    <FormField label={label} id={id} description={value && seconds(value) >= 0 ? describeSeconds(seconds(value)) : 'For example PT15M or P2D.'}>
      {(ids) => (
        <div className="flex flex-wrap gap-2">
          <Select id={ids.input} data-field={field} value={custom ? 'custom' : value} disabled={disabled} aria-describedby={ids.describedBy}
            onChange={(e) => { if (e.target.value !== 'custom') onChange(e.target.value) }}>
            {value === '' ? <option value="">Choose</option> : null}
            {presets.map((p) => <option key={p} value={p}>{describeSeconds(seconds(p))}</option>)}
            <option value="custom">Other…</option>
          </Select>
          {custom || value === '' ? (
            <CommitInput aria-label={`${label} (ISO-8601)`} className="w-32" value={value} disabled={disabled} spellCheck={false} onCommit={(v) => onChange(v.trim().toUpperCase())} />
          ) : null}
        </div>
      )}
    </FormField>
  )
}
