import { Upload } from 'lucide-react'
import { useRef, useState } from 'react'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import type { DecisionAssetContent, SharedAsset } from '@/api/types'
import { FormField } from '@/components/common/FormField'
import { toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'

type Converted = Awaited<ReturnType<typeof endpoints.convertDecisionDmn>>

/**
 * A shared decision from a standard DMN file (docs/build/S121): the server converts the file for review, then the
 * existing create or add-version call stores it. With `asset` the file becomes its next version; without, a new shared
 * decision with the key and name entered here.
 */
export function DecisionDmnImport({ ws, asset, onSaved }: { ws: string; asset?: SharedAsset; onSaved: (saved: SharedAsset) => void | Promise<void> }) {
  const input = useRef<HTMLInputElement>(null)
  const [xml, setXml] = useState<string | null>(null)
  const [file, setFile] = useState('')
  const [converted, setConverted] = useState<Converted | null>(null)
  const [choice, setChoice] = useState('')
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<string | null>(null)

  const convert = async (text: string, decisionId?: string) => {
    setErrors([])
    setStatus(null)
    setBusy(true)
    try {
      const result = await endpoints.convertDecisionDmn(ws, text, decisionId)
      setConverted(result)
      setChoice(result.decisionId ?? result.decisions.find((d) => d.table)?.id ?? result.decisions[0]?.id ?? '')
      if (result.content && !asset) {
        setName((old) => old || (result.name ?? ''))
        setKey((old) => old || (result.decisionId ?? '').toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64))
      }
    } catch (cause) {
      setConverted(null)
      const problem = toProblem(cause)
      setErrors(isProblemError(cause) && cause.fieldErrors.length ? cause.fieldErrors.map((f) => f.message) : [problem.detail ?? problem.title])
    } finally {
      setBusy(false)
    }
  }

  const pick = async (picked: File | undefined) => {
    if (!picked) return
    const text = await picked.text()
    setXml(text)
    setFile(picked.name)
    setKey('')
    setName('')
    await convert(text)
    if (input.current) input.current.value = ''
  }

  const save = async () => {
    if (!converted?.content) return
    setErrors([])
    setBusy(true)
    try {
      const content = converted.content as DecisionAssetContent
      const changeNote = note.trim() || `Imported from ${file}`
      const saved = asset
        ? await endpoints.addDecisionAssetVersion(ws, asset.asset.key, { expectedVersion: asset.asset.latestVersion, content, changeNote })
        : await endpoints.createDecisionAsset(ws, { key: key.trim(), name: name.trim(), content, changeNote })
      setStatus(asset ? `Saved ${file} as version ${saved.asset.latestVersion}.` : `Created ${saved.asset.name} from ${file}.`)
      setConverted(null)
      setXml(null)
      await onSaved(saved)
    } catch (cause) {
      const problem = toProblem(cause)
      setErrors(isProblemError(cause) && cause.fieldErrors.length ? cause.fieldErrors.map((f) => f.message) : [problem.detail ?? problem.title])
    } finally {
      setBusy(false)
    }
  }

  const label = asset ? 'New version from DMN' : 'Create from DMN'
  return (
    <section aria-label={label} className="space-y-2" data-testid="decision-dmn-import">
      <Button type="button" variant="secondary" size="sm" loading={busy && !converted} disabled={busy} onClick={() => input.current?.click()}>
        <Upload className="size-4" aria-hidden="true" /> {label}
      </Button>
      <input ref={input} type="file" accept=".dmn,.xml,application/xml,text/xml" className="sr-only" tabIndex={-1} aria-label={`DMN file: ${label}`} onChange={(e) => void pick(e.target.files?.[0])} />
      {converted && !converted.content && xml ? (
        <div className="flex flex-wrap items-end gap-2">
          <FormField label={`${file} has several decisions. Import`} id={`dmn-shared-choice-${asset?.asset.key ?? 'new'}`}>
            {(ids) => (
              <Select id={ids.input} value={choice} onChange={(e) => setChoice(e.target.value)}>
                {converted.decisions.map((d) => <option key={d.id} value={d.id}>{d.name === d.id ? d.id : `${d.name} (${d.id})`}{d.table ? '' : ' (not a table)'}</option>)}
              </Select>
            )}
          </FormField>
          <Button type="button" size="sm" disabled={!choice || busy} onClick={() => void convert(xml, choice)}>Read this decision</Button>
        </div>
      ) : null}
      {converted?.content ? (
        <form className="space-y-2 rounded-control border border-border p-3" aria-label={`Save ${file}`} onSubmit={(e) => { e.preventDefault(); void save() }}>
          <p className="text-sm">
            {file}: decision {converted.name ?? converted.decisionId}, {converted.content.rows.length} {converted.content.rows.length === 1 ? 'row' : 'rows'}, hit policy {converted.content.hitPolicy}.
          </p>
          {converted.notes.length ? <ul className="list-disc pl-5 text-xs text-muted-foreground">{converted.notes.map((n) => <li key={n}>{n}</li>)}</ul> : null}
          {!asset ? (
            <>
              <FormField label="Shared name" required id="dmn-shared-name">
                {(ids) => <Input id={ids.input} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />}
              </FormField>
              <FormField label="Shared key" required id="dmn-shared-key" description="Lowercase letters, digits and dashes.">
                {(ids) => <Input id={ids.input} value={key} maxLength={64} spellCheck={false} onChange={(e) => setKey(e.target.value)} />}
              </FormField>
            </>
          ) : null}
          <FormField label="Change note" optionalText id={`dmn-shared-note-${asset?.asset.key ?? 'new'}`}>
            {(ids) => <Input id={ids.input} value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />}
          </FormField>
          <div className="flex gap-2">
            <Button type="submit" variant="primary" loading={busy} disabled={busy || (!asset && (!name.trim() || !key.trim()))}>{asset ? `Save as version ${asset.asset.latestVersion + 1}` : 'Create shared decision'}</Button>
            <Button type="button" variant="ghost" onClick={() => { setConverted(null); setXml(null) }}>Cancel</Button>
          </div>
        </form>
      ) : null}
      {status ? <p role="status" className="text-sm text-muted-foreground">{status}</p> : null}
      {errors.length ? (
        <Alert tone="danger">
          <ul className="list-disc pl-5 text-sm">{errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
        </Alert>
      ) : null}
    </section>
  )
}
