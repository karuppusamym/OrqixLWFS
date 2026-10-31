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
import { BlueprintReview } from './BlueprintReview'
import { JobReview } from './JobReview'
import { PegaNativeReview } from './PegaNativeReview'
import { ProjectImportForm } from './ProjectImportForm'

const SOURCE_GUIDANCE = {
  bpmn: 'Upload a Camunda 7 or 8 BPMN model. Review unsupported elements and assignment mappings before creating a draft.',
  dmn: 'Upload a DMN decision table. Review supported hit policies and typed inputs before conversion.',
  camundaForm: 'Analyze a Camunda form, then attach the reviewed mapping to its BPMN import.',
  cmmn: 'Inventory only: inspect the case model and findings. This import cannot create or publish a case.',
  pegaRules: 'Only the supported native-rule grammar is converted. Unsupported rules block conversion; review ruleset, class, group and approval mappings.',
  pegaArchive: 'Inventory the archive, then supply a reviewed mapping package linked to its checksum.',
  pegaXpdl: 'Convert supported XPDL 2.0 flows after reviewing assignments and routes.',
  pegaBlueprint: 'Transcribe one case type from a Blueprint PDF. Review each element against its source page before conversion.',
  pegaMapping: 'Upload a reviewed mapping package. Its source checksum and mapped behavior are validated before a draft is created.',
} as const

export function MigrationPage() {
  const { workspace, can } = useWorkspace()
  const { migrationId } = useParams()
  const navigate = useNavigate()
  const [format, setFormat] = useState<'bpmn' | 'dmn' | 'camundaForm' | 'pegaArchive' | 'pegaMapping' | 'pegaXpdl' | 'cmmn' | 'pegaBlueprint' | 'pegaRules'>('bpmn')
  const [file, setFile] = useState<File | null>(null)
  const [caseType, setCaseType] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const job = useQuery({ queryKey: ['ws', workspace.id, 'migration', migrationId],
    queryFn: () => endpoints.getMigration(workspace.id, migrationId!), enabled: !!migrationId && can('workflow.read') })
  if (!can('workflow.edit') && !can('workflow.read')) return <Alert tone="warning">You need workflow access to view migrations.</Alert>
  const upload = async () => {
    if (!file) return
    setPending(true); setError(null)
    try {
      if (format === 'pegaBlueprint') {
        if (file.size > 25 * 1_048_576) throw new Error('Choose a PDF of at most 25 MiB.')
        const result = await endpoints.uploadBlueprintMigration(workspace.id, file, caseType)
        navigate(`/w/${workspace.id}/migrations/${result.id}`)
        return
      }
      if (file.size > 1_048_576) throw new Error('Choose a source file of at most 1 MiB.')
      let content: string
      if (format === 'pegaArchive' || format === 'pegaRules') {
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
  // On a report page the form follows the report: a Pega inventory is answered by uploading its reviewed mapping package.
  const uploadForm = <form className="my-6 space-y-4 rounded-card border border-border bg-surface p-5" onSubmit={(event) => { event.preventDefault(); void upload() }}>
      <h2>{migrationId ? 'Analyze another source file' : 'Analyze a source file'}</h2>
      <Label htmlFor="migration-format">Source format</Label>
      <Select id="migration-format" value={format} onChange={(e) => { setFormat(e.target.value as typeof format); setFile(null); setCaseType(''); setError(null) }}>
        <option value="bpmn">Camunda 7 / Camunda 8 / BPMN</option>
        <option value="dmn">Camunda DMN decision table (.dmn)</option>
        <option value="camundaForm">Camunda form (.form), to attach to a BPMN file</option>
        <option value="cmmn">CMMN 1.1 case model (.cmmn), inventory only</option>
        <option value="pegaRules">Native Pega rule archive, automatic conversion (.zip/.rap/.jar)</option>
        <option value="pegaArchive">Pega application archive for manual mapping (.zip)</option>
        <option value="pegaXpdl">Pega flow export (XPDL 2.0, .xpdl/.xml)</option>
        <option value="pegaBlueprint">Pega Blueprint (PDF)</option>
        <option value="pegaMapping">Reviewed Pega mapping package (.json)</option>
      </Select>
      <p role="status" className="text-sm text-muted-foreground">{SOURCE_GUIDANCE[format]}</p>
      <Label htmlFor="migration-file">{format === 'pegaBlueprint' ? 'Source file (PDF, maximum 25 MiB and 60 pages)' : 'Source file (maximum 1 MiB)'}</Label>
      <Input key={format} id="migration-file" type="file" accept={format === 'bpmn' ? '.bpmn,.xml' : format === 'dmn' ? '.dmn,.xml' : format === 'camundaForm' ? '.form,.json' : format === 'cmmn' ? '.cmmn,.xml' : format === 'pegaArchive' ? '.zip,.rap' : format === 'pegaRules' ? '.zip,.rap,.jar' : format === 'pegaXpdl' ? '.xpdl,.xml' : format === 'pegaBlueprint' ? '.pdf,application/pdf' : '.json'} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      {format === 'pegaBlueprint' ? <>
        <p className="text-sm text-muted-foreground">Only page text, its layout and the colour of step icons are read; scripts, attachments and images are ignored, and encrypted PDFs are refused. Pega&apos;s Blueprint export (&ldquo;Application Overview Document&rdquo;) is read stage by stage; other PDFs are read as a generic outline. Every transcribed element must be reviewed against its source page.</p>
        <Label htmlFor="migration-case-type">Case type (optional)</Label>
        <Input id="migration-case-type" value={caseType} maxLength={200} placeholder="The first case type in the blueprint" onChange={(e) => setCaseType(e.target.value)} aria-describedby="migration-case-type-hint" />
        <p id="migration-case-type-hint" className="text-sm text-muted-foreground">A blueprint can define several case types; one import transcribes one. Enter its name as the blueprint shows it, or leave this empty for the first. The findings name the others.</p>
      </> : null}
      <Button type="submit" variant="primary" loading={pending} disabled={!file}>Analyze source</Button>
      {error ? <Alert tone="danger">{error}</Alert> : null}
    </form>
  return <>
    <PageHeader title="Import workflow" description="Analyze source behavior, resolve mappings, then create a draft for review." actions={<Button asChild><Link to={`/w/${workspace.id}/workflows`}>Back to workflows</Link></Button>} />
    <ol aria-label="Import journey" className="mb-4 grid gap-2 sm:grid-cols-4">
      {['1. Analyze source', '2. Resolve findings', '3. Create draft', '4. Test and review'].map(step => <li key={step} className="rounded-control border border-border bg-surface px-4 py-3 text-sm font-semibold">{step}</li>)}
    </ol>
    <p className="text-sm text-muted-foreground">Imports create drafts for review. Live instances, history and credentials stay in the source system.</p>
    <details className="mt-3 rounded-control border border-border p-3"><summary className="cursor-pointer text-sm font-semibold">Supported formats and conversion limits</summary><div className="mt-3"><Alert tone="info" live="off">Camunda 7/8 BPMN and DMN tables can be converted within the supported subset. CMMN 1.1 case files can be inventoried but cannot be converted or published yet. Pega XPDL 2.0 flow exports have a bounded automatic converter with explicit assignment and route review. A Pega Blueprint PDF is transcribed from its page text and layout for mandatory human review, one case type at a time; only the reviewed transcription is converted. A native Pega rule archive is decoded with a strict synthetic grammar, resolved against its own ruleset stack and class inheritance, and converted per rule with explicit group and approval mappings; unsupported rules block. Native Pega archives can also be inventoried for a reviewed checksum-linked mapping package. Live instances, history and credentials stay in the source system.</Alert></div></details>
    {can('workflow.edit') && !migrationId ? uploadForm : null}
    {can('workflow.edit') && !migrationId ? <ProjectImportForm /> : null}
    {migrationId && job.isPending ? <LoadingBlock label="Loading compatibility report" /> : null}
    {job.isError ? <QueryError error={job.error} onRetry={() => void job.refetch()} what="the migration report" /> : null}
    {job.data ? job.data.report.adapterVersion.startsWith('pega-blueprint-pdf') ? <BlueprintReview key={job.data.id} job={job.data} editable={can('workflow.edit')} />
      : job.data.report.adapterVersion.startsWith('pega-native-') ? <PegaNativeReview key={job.data.id} job={job.data} editable={can('workflow.edit')} />
      : <JobReview key={job.data.id} job={job.data} editable={can('workflow.edit')} /> : null}
    {can('workflow.edit') && migrationId && job.data ? uploadForm : null}
  </>
}
