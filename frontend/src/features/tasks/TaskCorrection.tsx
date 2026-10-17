import { Pencil } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { fingerprintOf, useIdempotencyKey } from '@/api/idempotency'
import type { ProblemError } from '@/api/problem'
import { asRecord, type TaskDetail } from '@/api/types'
import { useAnnounce } from '@/components/common/Announcer'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Input, Select, Textarea } from '@/components/ui/input'
import type { FieldDef } from '@/lib/form/schema'

/**
 * The person holding an approval may correct the request fields the workflow declared correctable (docs/build/S42).
 * Only the declared single-value fields are offered, a reason is required, and the server records before and after.
 */
export function TaskCorrection({ ws, task, fields, onDone }: { ws: string; task: TaskDetail; fields: FieldDef[]; onDone: () => Promise<void> }) {
  const announce = useAnnounce()
  const key = useIdempotencyKey()
  const declared = fields.filter((f) => task.form.correctableFields?.includes(f.key))
  const current = asRecord(task.request.input)
  const [values, setValues] = useState<Record<string, string>>({})
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  if (declared.length === 0) return null

  const text = (f: FieldDef) => (values[f.key] ?? String(current[f.key] ?? ''))
  const typed = (f: FieldDef, raw: string): string | number | boolean => {
    if (f.schema.type === 'number' || f.schema.type === 'integer') return Number(raw)
    if (f.schema.type === 'boolean') return raw === 'true'
    return raw
  }
  const changes = Object.fromEntries(
    declared.filter((f) => values[f.key] !== undefined && values[f.key] !== String(current[f.key] ?? '')).map((f) => [f.key, typed(f, values[f.key] as string)]),
  )
  // Server field errors point at /changes/<field>.
  const errors: Record<string, string> = {}
  for (const fe of error?.fieldErrors ?? []) {
    const match = /^\/changes\/([^/]+)/.exec(fe.pointer)
    if (match?.[1] && !(match[1] in errors)) errors[match[1]] = fe.message
  }

  const submit = async () => {
    if (Object.keys(changes).length === 0) return setError(toProblem(new Error('Change at least one value.')))
    if (!reason.trim()) return setReasonError('Add a reason. It is recorded with the change.')
    setReasonError(undefined)
    setPending(true)
    setError(null)
    try {
      const idem = key.keyFor(fingerprintOf(task.id, task.version, JSON.stringify(changes), reason.trim()))
      await endpoints.correctTaskInput(ws, task.id, { expectedVersion: task.version, reason: reason.trim(), changes }, idem)
      setValues({})
      setReason('')
      await onDone()
      announce('The request details were corrected.')
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <section aria-labelledby="task-correct-heading" className="space-y-3 rounded-card border border-border p-4" data-testid="task-correction">
      <h3 id="task-correct-heading" className="flex items-center gap-1.5 text-sm text-muted-foreground uppercase">
        <Pencil className="size-4" aria-hidden="true" /> Correct request details
      </h3>
      <p className="text-sm text-muted-foreground">You can fix these details before you decide. The change and your reason are recorded, and later steps use the corrected values.</p>
      {declared.map((f) => (
        <FormField key={f.key} label={f.label} id={`correct-${f.key}`} error={errors[f.key]}>
          {(ids) =>
            f.schema.type === 'boolean' ? (
              <Select id={ids.input} value={text(f)} onChange={(e) => setValues((old) => ({ ...old, [f.key]: e.target.value }))}>
                <option value="true">Yes</option>
                <option value="false">No</option>
              </Select>
            ) : f.schema.enum ? (
              <Select id={ids.input} value={text(f)} onChange={(e) => setValues((old) => ({ ...old, [f.key]: e.target.value }))}>
                {f.schema.enum.map((option) => (
                  <option key={String(option)} value={String(option)}>
                    {String(option)}
                  </option>
                ))}
              </Select>
            ) : (
              <Input id={ids.input} type={f.schema.type === 'number' || f.schema.type === 'integer' ? 'number' : 'text'} step="any" value={text(f)} onChange={(e) => setValues((old) => ({ ...old, [f.key]: e.target.value }))} aria-describedby={ids.describedBy} />
            )
          }
        </FormField>
      ))}
      <FormField label="Reason for the correction" required id="correct-reason" error={reasonError}>
        {(ids) => <Textarea id={ids.input} value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={500} aria-required aria-invalid={reasonError ? true : undefined} aria-describedby={ids.describedBy} />}
      </FormField>
      {error && !error.fieldErrors?.length ? <ProblemAlert error={error} /> : null}
      <Button variant="secondary" onClick={() => void submit()} loading={pending} disabled={Object.keys(changes).length === 0}>
        Save corrections
      </Button>
    </section>
  )
}
