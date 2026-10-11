import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import * as endpoints from '@/api/endpoints'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { JobReview } from './JobReview'
import { ProjectImportForm } from './ProjectImportForm'

export function MigrationPage() {
  const { workspace, can } = useWorkspace()
  const { migrationId } = useParams()
  const navigate = useNavigate()
  const [format, setFormat] = useState<'bpmn' | 'dmn' | 'camundaForm' | 'pegaArchive' | 'pegaMapping'>('bpmn')
  const [file, setFile] = useState<File | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const job = useQuery({ queryKey: ['ws', workspace.id, 'migration', migrationId],
    queryFn: () => endpoints.getMigration(workspace.id, migrationId!), enabled: !!migrationId && can('workflow.read') })
  if (!can('workflow.edit') && !can('workflow.read')) return <Alert tone="warning">You need workflow access to view migrations.</Alert>
  const upload = async () => {
    if (!file) return
    setPending(true); setError(null)
    try {
      if (file.size > 1_048_576) throw new Error('Choose a source file of at most 1 MiB.')
      let content: string
      if (format === 'pegaArchive') {
        const bytes = new Uint8Array(await file.arrayBuffer())
        let binary = ''
        for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
        content = btoa(binary)
      } else content = await file.text()
      const result = await endpoints.uploadMigration(workspace.id, { fileName: file.name, content, sourceFormat: format })
      navigate(`/w/${workspace.id}/migrations/${result.id}`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'The source could not be analyzed.') }
    finally { setPending(false) }
  }
  return <>
    <PageHeader title="Import workflow" description="Analyze source behavior, resolve mappings, then create a draft for review." actions={<Button asChild><Link to={`/w/${workspace.id}/workflows`}>Back to workflows</Link></Button>} />
    <Alert tone="info" live="off">Camunda 7/8 BPMN (including Camunda 8 forms and the REST connector) and DMN decision tables can be converted within the supported subset. Pega XML archives include rule identity, dependency evidence and reviewed mapping suggestions; a reviewed, checksum-linked manual mapping package creates the target draft. Live instances, history and credentials stay in the source system.</Alert>
    {can('workflow.edit') ? <form className="my-6 space-y-4 rounded-card border border-border bg-surface p-5" onSubmit={(event) => { event.preventDefault(); void upload() }}>
      <h2>Analyze a source file</h2>
      <Label htmlFor="migration-format">Source format</Label>
      <Select id="migration-format" value={format} onChange={(e) => { setFormat(e.target.value as typeof format); setFile(null); setError(null) }}>
        <option value="bpmn">Camunda 7 / Camunda 8 / BPMN</option>
        <option value="dmn">Camunda DMN decision table (.dmn)</option>
        <option value="camundaForm">Camunda form (.form), to attach to a BPMN file</option>
        <option value="pegaArchive">Pega application archive (.zip)</option>
        <option value="pegaMapping">Reviewed Pega mapping package (.json)</option>
      </Select>
      <Label htmlFor="migration-file">Source file (maximum 1 MiB)</Label>
      <Input key={format} id="migration-file" type="file" accept={format === 'bpmn' ? '.bpmn,.xml' : format === 'dmn' ? '.dmn,.xml' : format === 'camundaForm' ? '.form,.json' : format === 'pegaArchive' ? '.zip,.rap' : '.json'} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      <Button type="submit" variant="primary" loading={pending} disabled={!file}>Analyze source</Button>
      {error ? <Alert tone="danger">{error}</Alert> : null}
    </form> : null}
    {can('workflow.edit') && !migrationId ? <ProjectImportForm /> : null}
    {migrationId && job.isPending ? <LoadingBlock label="Loading compatibility report" /> : null}
    {job.isError ? <QueryError error={job.error} onRetry={() => void job.refetch()} what="the migration report" /> : null}
    {job.data ? <JobReview key={job.data.id} job={job.data} editable={can('workflow.edit')} /> : null}
  </>
}
