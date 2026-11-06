import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { FormRenderer } from '@/components/form/FormRenderer'
import { getGuestForm, submitGuestResponse, uploadGuestFile, type GuestFile, type GuestForm } from '@/api/guest'
import { isProblemError } from '@/api/problem'
import { buildFields, parseFormSchema } from '@/lib/form/schema'
import { emptyValues, validateForm, type FieldErrors, type RawValue, type RawValues } from '@/lib/form/values'
import { uuid } from '@/lib/text'

const TOKEN_KEY = 'orqix.guest-token'

/** The link token: from the URL fragment (then removed from the address bar), or kept for this tab after a reload. */
function readToken(ws: string): string {
  const fromHash = window.location.hash.replace(/^#/, '')
  if (/^ogf_[A-Za-z0-9_-]{43}$/.test(fromHash)) {
    try { sessionStorage.setItem(`${TOKEN_KEY}:${ws}`, fromHash) } catch { /* private mode: the link still works once */ }
    window.history.replaceState(null, '', window.location.pathname)
    return fromHash
  }
  try { return sessionStorage.getItem(`${TOKEN_KEY}:${ws}`) ?? '' } catch { return '' }
}

function formSchemaOf(form: GuestForm) {
  const properties: Record<string, unknown> = {}
  for (const q of form.questions) {
    properties[q.name] = q.type === 'date' ? { type: 'string', format: 'date', title: q.title } : { type: q.type, title: q.title, ...(q.type === 'string' ? { maxLength: 4000 } : {}) }
  }
  return parseFormSchema({ type: 'object', additionalProperties: false, properties, required: form.questions.filter((q) => q.required).map((q) => q.name) })
}

/**
 * Guest response form (CG-F05, docs/build/S140): a minimal page for a person outside the organization. It shows only
 * the step's title, instructions and questions, never the request's data, and sends the answer once.
 */
export function GuestResponsePage() {
  const { workspaceId = '' } = useParams()
  const [token] = useState(() => readToken(workspaceId))
  const [form, setForm] = useState<GuestForm | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [values, setValues] = useState<RawValues>({})
  const [errors, setErrors] = useState<FieldErrors>({})
  const [files, setFiles] = useState<GuestFile[]>([])
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const key = useRef(uuid())

  useEffect(() => {
    document.title = 'Your response'
    if (!token) { setProblem('This link is not complete. Open the link from your message again.'); return }
    getGuestForm(workspaceId, token)
      .then((f) => { setForm(f); setFiles(f.uploaded) })
      .catch((e: unknown) => setProblem(isProblemError(e) && e.status === 404
        ? 'This link does not work. It may have been replaced or withdrawn; ask the person who sent it for a new one.'
        : 'The form cannot be loaded right now. Try again in a moment.'))
  }, [workspaceId, token])

  const schema = useMemo(() => (form ? formSchemaOf(form) : null), [form])
  const fields = useMemo(() => (schema ? buildFields(schema, {}) : []), [schema])
  useEffect(() => { setValues(emptyValues(fields)) }, [fields])

  if (problem) return <Shell><Alert tone="warning" title="Link not available">{problem}</Alert></Shell>
  if (!form || !schema) return <Shell><p role="status">Loading the form…</p></Shell>
  if (done || form.state === 'submitted') return <Shell title={form.title}><Alert tone="success" title="Thank you">Your response was sent. You can close this page.</Alert></Shell>
  if (form.state !== 'open') return <Shell title={form.title}><Alert tone="warning" title="This form is closed">{form.state === 'expired' ? 'The link expired. Ask the person who sent it for a new one.' : 'An answer is no longer needed.'}</Alert></Shell>

  const rules = form.files
  const change = (k: string, v: RawValue) => { setValues((prev) => ({ ...prev, [k]: v })); key.current = uuid() }

  const addFile = async (file: File) => {
    setBusy(true)
    try {
      const added = await uploadGuestFile(workspaceId, token, file)
      setFiles((prev) => [...prev, added])
      key.current = uuid()
    } catch (e) {
      setErrors((prev) => ({ ...prev, _files: isProblemError(e) ? (e.fieldErrors[0]?.message ?? e.detail ?? e.title) : 'The file could not be added.' }))
    } finally {
      setBusy(false)
    }
  }

  const submit = async () => {
    const result = validateForm(schema, {}, values)
    const fileError = rules?.required && files.length === 0 ? 'Add at least one file.' : undefined
    setErrors({ ...result.errors, ...(fileError ? { _files: fileError } : {}) })
    if (!result.valid || fileError) return
    setBusy(true)
    try {
      await submitGuestResponse(workspaceId, token, key.current, result.payload, files.map((f) => f.id))
      try { sessionStorage.removeItem(`${TOKEN_KEY}:${workspaceId}`) } catch { /* nothing kept */ }
      setDone(true)
    } catch (e) {
      if (isProblemError(e) && e.fieldErrors.length) {
        const next: FieldErrors = {}
        for (const fe of e.fieldErrors) next[fe.pointer.split('/')[2] ?? '_files'] = fe.message
        setErrors(next)
      } else {
        setErrors({ _form: isProblemError(e) ? (e.detail ?? e.title) : 'The response could not be sent. Try again.' })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <Shell title={form.title}>
      {form.instructions ? <p className="whitespace-pre-line text-sm">{form.instructions}</p> : null}
      {form.expiresAt ? <p className="text-sm text-muted-foreground">This link works once, until {new Date(form.expiresAt).toLocaleString()}.</p> : null}
      <form className="space-y-5" noValidate onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <FormRenderer fields={fields} values={values} onChange={change} errors={errors} idPrefix="guest" disabled={busy} />
        {rules ? (
          <fieldset className="space-y-2">
            <legend className="text-sm font-semibold">Files{rules.required ? ' (required)' : ''}</legend>
            <p className="text-sm text-muted-foreground">Add up to {rules.maxFiles} file{rules.maxFiles === 1 ? '' : 's'}{rules.acceptTypes.length ? ` (${rules.acceptTypes.join(', ')})` : ''}.</p>
            <ul className="text-sm">{files.map((f) => <li key={f.id}>{f.name}</li>)}</ul>
            {files.length < rules.maxFiles ? (
              <input type="file" aria-label="Add a file" disabled={busy} onChange={(e) => { const f = e.target.files?.[0]; if (f) void addFile(f); e.target.value = '' }} />
            ) : null}
            {errors._files ? <p className="text-sm font-medium text-danger" role="alert">{errors._files}</p> : null}
          </fieldset>
        ) : null}
        {errors._form ? <Alert tone="danger" title="Not sent">{errors._form}</Alert> : null}
        <Button type="submit" variant="primary" loading={busy}>Send response</Button>
      </form>
    </Shell>
  )
}

function Shell({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <main className="mx-auto max-w-xl space-y-5 px-4 py-10">
      <h1 className="text-xl font-semibold">{title ?? 'Your response'}</h1>
      {children}
    </main>
  )
}
