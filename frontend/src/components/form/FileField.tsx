import { FileUp, Paperclip, X } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useWsId } from '@/app/workspace'
import { acceptAttribute, checkFile, describeTypes, discardFormFile, formatBytes, MAX_FILE_BYTES, uploadFormFile } from '@/api/attachments'
import { isProblemError } from '@/api/problem'
import { describeProblem } from '@/lib/problemMessages'
import { FormField } from '@/components/common/FormField'
import { Button } from '@/components/ui/button'
import type { FieldDef } from '@/lib/form/schema'

interface Chosen {
  id: string
  name: string
  size: number
  scanStatus: string
}

/** The sentence that names what the field accepts, shown before anyone chooses a file. */
export function fileLimitsText(field: FieldDef): string {
  const max = Math.min(field.schema.maxFileBytes ?? MAX_FILE_BYTES, MAX_FILE_BYTES)
  return `Accepted: ${describeTypes(field.schema.acceptTypes)}. Largest file: ${formatBytes(max)}.`
}

/**
 * A file question on a request form (format attachment, docs/build/S17 section 1). Choosing a file uploads it at once;
 * the form value is the ID of the upload, and starting the request attaches the file to it. Removing the file deletes
 * the upload. Progress is announced in text, errors name what is wrong in plain language.
 */
export function FileField({ field, value, onChange, error, id, disabled, onBusyChange }: {
  field: FieldDef
  value: string
  onChange: (value: string) => void
  error: string | undefined
  id: string
  disabled: boolean | undefined
  onBusyChange?: (busy: boolean) => void
}) {
  const ws = useWsId()
  const input = useRef<HTMLInputElement>(null)
  const statusId = useId()
  const [chosen, setChosen] = useState<Chosen | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const active = useRef(false)
  const generation = useRef<object>({})
  const busyRef = useRef(false)
  const busyCallback = useRef(onBusyChange)
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => {
    active.current = true
    return () => { active.current = false; generation.current = {}; if (busyRef.current) busyCallback.current?.(false) }
  }, [ws, field.key])
  const max = Math.min(field.schema.maxFileBytes ?? MAX_FILE_BYTES, MAX_FILE_BYTES)

  async function choose(file: File | undefined) {
    if (!file) return
    setProblem(null)
    const local = checkFile(file, field.schema.acceptTypes, max)
    if (local) {
      setProblem(local)
      return
    }
    setProgress(0)
    busyRef.current = true
    const ticket = {}
    generation.current = ticket
    onBusyChange?.(true)
    try {
      const staged = await uploadFormFile(ws, file, { onProgress: (next) => { if (active.current && ticket === generation.current) setProgress(next) } })
      if (!active.current || ticket !== generation.current) { void discardFormFile(ws, staged.id).catch(() => undefined); return }
      if (staged.scanStatus === 'infected' || staged.scanStatus === 'error') {
        void discardFormFile(ws, staged.id).catch(() => undefined)
        setProblem('This file was blocked by the virus scan. Choose another file.')
        return
      }
      if (value) void discardFormFile(ws, value).catch(() => undefined)
      setChosen({ id: staged.id, name: staged.name, size: staged.sizeBytes, scanStatus: staged.scanStatus })
      onChange(staged.id)
    } catch (cause) {
      if (!active.current || ticket !== generation.current) return
      if (isProblemError(cause)) setProblem(cause.fieldErrors[0]?.message ?? describeProblem(cause).message)
      else setProblem('The file could not be uploaded. Try again.')
    } finally {
      if (active.current && ticket === generation.current) {
        busyRef.current = false
        setProgress(null)
        busyCallback.current?.(false)
        if (input.current) input.current.value = ''
      }
    }
  }

  function remove() {
    if (value) void discardFormFile(ws, value).catch(() => undefined)
    setChosen(null)
    setProblem(null)
    onChange('')
    input.current?.focus()
  }

  const shownError = problem ?? error
  const busy = progress !== null
  return (
    <FormField label={field.label} description={<>{field.description} {fileLimitsText(field)}</>} descriptionPlacement={field.helpAbove === undefined ? undefined : field.helpAbove ? 'above' : 'below'} error={shownError} required={field.required} id={id}>
      {(ids) => (
        <div className="space-y-2">
          <input
            ref={input}
            id={ids.input}
            type="file"
            className="sr-only"
            tabIndex={-1}
            accept={acceptAttribute(field.schema.acceptTypes)}
            disabled={disabled || busy}
            aria-describedby={[ids.describedBy, statusId].filter(Boolean).join(' ') || undefined}
            aria-invalid={shownError ? true : undefined}
            onChange={(e) => void choose(e.target.files?.[0])}
          />
          {value ? (
            <div className="flex flex-wrap items-center gap-3 rounded-control border border-border px-3 py-2" data-testid={`file-chosen-${field.key}`}>
              <Paperclip className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <span className="min-w-0 flex-1 wrap-anywhere text-sm font-medium">
                {chosen ? <>{chosen.name} <span className="font-normal text-muted-foreground">({formatBytes(chosen.size)})</span></> : 'A file is attached'}
              </span>
              <Button type="button" variant="ghost" size="sm" disabled={disabled || busy} onClick={remove} aria-label={`Remove the file from ${field.label}`}>
                <X className="size-4" aria-hidden="true" /> Remove
              </Button>
            </div>
          ) : (
            <Button type="button" variant="secondary" disabled={disabled || busy} onClick={() => input.current?.click()}>
              <FileUp className="size-4" aria-hidden="true" /> {busy ? 'Uploading...' : 'Choose a file'}
            </Button>
          )}
          <div id={statusId} role="status" aria-live="polite" className="text-xs text-muted-foreground">
            {busy ? (
              <span className="flex items-center gap-2">
                <progress className="h-2 w-40" value={Math.round((progress ?? 0) * 100)} max={100} aria-label={`Uploading ${field.label}`} />
                {Math.round((progress ?? 0) * 100)}% uploaded
              </span>
            ) : value ? chosen?.scanStatus === 'notScanned' ? 'File uploaded. Virus scanning is not configured.' : 'File uploaded.' : null}
          </div>
        </div>
      )}
    </FormField>
  )
}
