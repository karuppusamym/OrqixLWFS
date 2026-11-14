import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import type { components } from '@/api/schema'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { fieldErrorMap } from '@/lib/problemMessages'
import { KEY_PATTERN, toKey } from '@/lib/text'
import { adminKeys } from './queries'

/** New organization (platform administrators): a name and a key. Workspaces are then created inside it. */
export function NewOrganizationDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (organization: components['schemas']['Organization']) => void }) {
  const client = useQueryClient()
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyTouched, setKeyTouched] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)
  const effectiveKey = keyTouched ? key : toKey(name)

  const submit = async () => {
    const next: Record<string, string> = {}
    if (!name.trim()) next.name = 'Give the organization a name.'
    if (!KEY_PATTERN.test(effectiveKey)) next.key = 'Use 2 to 64 lowercase letters, digits and dashes, starting with a letter and not ending with a dash.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setPending(true)
    setError(null)
    try {
      const created = await endpoints.createOrganization({ key: effectiveKey, name: name.trim() })
      await client.invalidateQueries({ queryKey: adminKeys.organizations })
      onCreated(created)
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (problem.code === 'DUPLICATE_KEY') setErrors({ key: 'An organization with this key already exists. Choose a different key.' })
      else if (Object.keys(mapped).length > 0) setErrors(mapped)
      else setError(problem)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="New organization"
      description="An organization groups workspaces, for example one per company or legal entity."
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>Create organization</Button>
        </>
      }
    >
      <div className="space-y-5">
        <FormField label="Name" required id="org-new-name" error={errors.name}>
          {(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={200} aria-invalid={errors.name ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />}
        </FormField>
        <FormField label="Key" required id="org-new-key" error={errors.key} description="Filled in from the name. Lowercase letters, digits and dashes. It cannot be changed later.">
          {(ids) => <Input id={ids.input} value={effectiveKey} onChange={(e) => { setKey(e.target.value); setKeyTouched(true) }} maxLength={64} aria-invalid={errors.key ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
        </FormField>
        {error ? <ProblemAlert error={error} context={{ action: 'create an organization' }} /> : null}
      </div>
    </Dialog>
  )
}
