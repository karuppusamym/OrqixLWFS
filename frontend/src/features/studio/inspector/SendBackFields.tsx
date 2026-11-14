import { useState } from 'react'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/input'
import { useStudio, useStudioContext } from '../StudioContext'
import { nodeById, sendBackTargets } from '../model/graph'
import { setLimitTarget, setMaxTraversals, setSendBack } from '../model/ops'
import type { Transition } from '../model/types'
import { CommitNumber, InspectorSection } from './controls'

/**
 * CG-E01 (docs/build/S138): "Send back to an earlier step" on a connection. The path then leads back to the chosen
 * step at most 1-10 times; once the limit is used up the step continues on its "limit reached" path, which starts at
 * the step the connection led to before.
 */
export function SendBackFields({ transition: t }: { transition: Transition }) {
  const def = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const [problem, setProblem] = useState<string | null>(null)
  const from = nodeById(def, t.from)
  if (!from || t.on === 'limitReached' || from.type === 'start' || from.type === 'end') return null
  const targets = sendBackTargets(def, t.from)
  const limit = def.transitions.find((x) => x.from === t.from && x.on === 'limitReached')
  const others = def.nodes.filter((n) => n.type !== 'start' && n.id !== t.from)

  const apply = (toId: string | null, max = t.maxTraversals ?? 2) => {
    let reason: string | null = null
    store.getState().apply((d) => {
      const result = setSendBack(d, t.id, toId, max)
      if (!result.ok) {
        reason = result.reason
        return d
      }
      return result.def
    })
    setProblem(reason)
  }

  if (!t.maxTraversals) {
    return (
      <InspectorSection title="Send back to an earlier step">
        <p className="text-sm text-muted-foreground">
          Instead of continuing, this path can send the request back to an earlier step, for example for rework. You choose how many times; after that the request continues where this path leads now.
        </p>
        <FormField label="Send back to" optionalText id={`send-back-${t.id}`}>
          {(ids) => (
            <Select id={ids.input} value="" disabled={readOnly} onChange={(e) => e.target.value && apply(e.target.value)}>
              <option value="">Do not send back</option>
              {targets.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.label}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        {problem ? <p className="text-sm text-danger-foreground" role="alert">{problem}</p> : null}
      </InspectorSection>
    )
  }

  return (
    <InspectorSection title="Send back to an earlier step">
      <FormField label="Send back to" id={`send-back-${t.id}`}>
        {(ids) => (
          <Select id={ids.input} value={t.to} disabled={readOnly} onChange={(e) => e.target.value && apply(e.target.value)}>
            {targets.map((n) => (
              <option key={n.id} value={n.id}>
                {n.label}
              </option>
            ))}
          </Select>
        )}
      </FormField>
      <FormField label="At most (times)" required id={`send-back-max-${t.id}`} description="1 to 10. Every time is a new visit: new tasks, new calls and new deadlines; separation of duties counts every visit.">
        {(ids) => (
          <CommitNumber
            id={ids.input}
            integer
            min={1}
            max={10}
            value={t.maxTraversals}
            disabled={readOnly}
            onCommit={(v) => v !== undefined && store.getState().apply((d) => setMaxTraversals(d, t.id, v))}
          />
        )}
      </FormField>
      <FormField label="When the limit is reached, continue at" required id={`send-back-limit-${t.id}`}>
        {(ids) => (
          <Select id={ids.input} value={limit?.to ?? ''} disabled={readOnly} onChange={(e) => e.target.value && store.getState().apply((d) => setLimitTarget(d, t.from, e.target.value))}>
            {limit ? null : <option value="">Choose a step</option>}
            {others.map((n) => (
              <option key={n.id} value={n.id}>
                {n.label}
              </option>
            ))}
          </Select>
        )}
      </FormField>
      {!readOnly ? (
        <Button variant="secondary" onClick={() => apply(null)}>
          Stop sending back
        </Button>
      ) : null}
      {problem ? <p className="text-sm text-danger-foreground" role="alert">{problem}</p> : null}
    </InspectorSection>
  )
}
