import { useState } from 'react'
import { useNavigate } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { isProblemError } from '@/api/problem'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export const PROJECT_MAX_FILES = 10
const FILE_MAX_BYTES = 1_048_576
const PROJECT_MAX_BYTES = 4_194_304

/** Checked before anything is read: the server enforces the same limits and stays authoritative. */
export function projectFileProblem(files: File[]): string | null {
  if (files.length === 0) return null
  if (files.length > PROJECT_MAX_FILES) return `Choose at most ${PROJECT_MAX_FILES} files.`
  if (files.some((f) => f.size > FILE_MAX_BYTES)) return 'Each file must be at most 1 MiB.'
  if (files.reduce((sum, f) => sum + f.size, 0) > PROJECT_MAX_BYTES) return 'The files together must be at most 4 MiB.'
  if (new Set(files.map((f) => f.name)).size !== files.length) return 'Each file needs a different name.'
  return null
}

/** Multi-file BPMN/DMN import; the server safely identifies XML namespaces and orders dependencies. */
export function ProjectImportForm() {
  const { workspace } = useWorkspace()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const problem = projectFileProblem(files)
  const submit = async () => {
    setPending(true); setError(null)
    try {
      const contents = await Promise.all(files.map(async (file) => ({ fileName: file.name, content: await file.text(), ...(file.name.toLowerCase().endsWith('.dmn') ? { sourceFormat: 'dmn' as const } : {}) })))
      const project = await endpoints.uploadMigrationProject(workspace.id, { name: name.trim(), files: contents })
      navigate(`/w/${workspace.id}/migrations/projects/${project.id}`)
    } catch (cause) {
      setError(isProblemError(cause) ? (cause.detail ?? cause.title) : cause instanceof Error ? cause.message : 'The project could not be analyzed.')
    } finally { setPending(false) }
  }
  return <form aria-label="Import a project" className="my-6 space-y-4 rounded-card border border-border bg-surface p-5" onSubmit={(event) => { event.preventDefault(); void submit() }}>
    <h2>Import a multi-file project</h2>
    <p className="text-sm text-muted-foreground">Choose the BPMN and DMN files of one application, up to {PROJECT_MAX_FILES} files. Children and decision files are converted before their callers. A DMN file may contain several supported decisions; explicitly choose one for its draft workflow, then review each caller's decision mapping. XML files are identified by their namespace.</p>
    <Label htmlFor="project-name">Project name</Label>
    <Input id="project-name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
    <Label htmlFor="project-files">BPMN files and DMN files (up to {PROJECT_MAX_FILES}, 1 MiB each)</Label>
    <Input id="project-files" type="file" multiple accept=".bpmn,.dmn,.xml" aria-describedby={problem ? 'project-files-problem' : files.length ? 'project-files-list' : undefined} aria-invalid={problem ? true : undefined}
      onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
    {files.length ? <ul id="project-files-list" aria-label="Selected files" className="list-disc pl-5 text-sm text-muted-foreground">{files.map((file) => <li key={file.name}>{file.name} ({Math.max(1, Math.round(file.size / 1024))} KiB)</li>)}</ul> : null}
    {problem ? <Alert tone="danger" id="project-files-problem">{problem}</Alert> : null}
    {error ? <Alert tone="danger">{error}</Alert> : null}
    <Button type="submit" variant="primary" loading={pending} disabled={!files.length || !name.trim() || !!problem}>Analyze project</Button>
  </form>
}
