import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatDateTime } from '@/lib/dates'
import { AiProblem, useAction } from './AiProblem'
import {
  aiSkillKeys, approveAiSkillVersion, AUTONOMY_LABEL, createAiSkillVersion, MODE_LABEL, retireAiSkillVersion, STATUS_LABEL, STATUS_TONE,
  submitAiSkillVersion, updateAiSkillVersion, useAiSkill, type AiSkill, type AiSkillVersion,
} from './api'
import { EvaluationPanel } from './EvaluationPanel'
import { PromotionsPanel } from './PromotionsPanel'
import { ShadowPanel } from './ShadowPanel'
import { SkillEditor } from './SkillEditor'

/**
 * One AI skill with its versions (S134 section 2): read the document, author a draft (workflow.edit), submit it, approve
 * it as a different person (ai.approve-skill, four eyes, reason required), retire a version (kill switch) and add a new
 * version; plus the version's evaluations, shadow dashboard with drift, and promotions.
 */
export function AiSkillDetailPage() {
  const { skillKey = '' } = useParams()
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const skill = useAiSkill(ws, skillKey)
  const [params, setParams] = useSearchParams()
  if (!can('workflow.read', 'ai.evaluate', 'ai.approve-skill')) {
    return <Alert tone="warning">You need permission to read workflows, run evaluations or approve AI skills to see AI skills.</Alert>
  }
  if (skill.isPending) return <LoadingBlock rows={4} label="Loading the AI skill" />
  if (skill.isError) return <QueryError error={skill.error} onRetry={() => void skill.refetch()} what="this AI skill" backTo={{ to: `/w/${ws}/ai-skills`, label: 'Back to AI skills' }} />
  const s = skill.data
  const versions = [...s.versions].sort((a, b) => b.version - a.version)
  const wanted = Number(params.get('v'))
  const version = versions.find((v) => v.version === wanted) ?? versions[0]
  return (
    <div className="space-y-6">
      <Link to={`/w/${ws}/ai-skills`} className="text-sm">Back to AI skills</Link>
      <PageHeader title={s.name} description={<><span className="font-mono">{s.key}</span>, {MODE_LABEL[s.mode]}, {s.approvedVersion ? `approved version ${s.approvedVersion} in use` : 'no approved version yet'}</>} />
      <nav aria-label="Versions" className="flex flex-wrap gap-2">
        {versions.map((v) => (
          <Button key={v.version} size="sm" variant={v.version === version?.version ? 'primary' : 'secondary'} aria-current={v.version === version?.version ? 'true' : undefined}
            onClick={() => setParams({ v: String(v.version) }, { replace: true })}>
            Version {v.version}: {STATUS_LABEL[v.status]}
          </Button>
        ))}
      </nav>
      {version ? <VersionView key={version.version} skill={s} version={version} onShowVersion={(n) => setParams({ v: String(n) }, { replace: true })} /> : null}
    </div>
  )
}

function VersionView({ skill, version, onShowVersion }: { skill: AiSkill; version: AiSkillVersion; onShowVersion: (n: number) => void }) {
  const { workspace, can, me } = useWorkspace()
  const ws = workspace.id
  const client = useQueryClient()
  const [editing, setEditing] = useState<'edit' | 'new' | null>(null)
  const [approving, setApproving] = useState(false)
  const [retiring, setRetiring] = useState(false)
  const [tab, setTab] = useState('document')
  const submit = useAction()
  const author = version.authorId === me.subject.id
  const inProgress = skill.versions.some((v) => v.status === 'draft' || v.status === 'submitted')
  const editable = version.status === 'draft' || version.status === 'submitted'
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: aiSkillKeys.one(ws, skill.key) }),
      client.invalidateQueries({ queryKey: aiSkillKeys.list(ws) }),
    ])
  }
  const latestDoc = [...skill.versions].sort((a, b) => b.version - a.version)[0]?.document ?? null

  if (editing) {
    return (
      <section aria-labelledby="edit-heading" className="space-y-3">
        <h2 id="edit-heading">{editing === 'new' ? `New version of ${skill.name}` : `Edit version ${version.version}`}</h2>
        {editing === 'edit' && version.status === 'submitted' ? <Alert tone="info">Saving a submitted version returns it to draft; submit it again afterwards.</Alert> : null}
        <SkillEditor
          initial={(editing === 'new' ? latestDoc : version.document) ?? null}
          keyLocked
          saveLabel={editing === 'new' ? 'Create draft version' : 'Save draft'}
          onCancel={() => setEditing(null)}
          onSave={async (document) => {
            if (editing === 'new') {
              const created = await createAiSkillVersion(ws, skill.key, document)
              await refresh()
              setEditing(null)
              onShowVersion(created.version)
            } else {
              await updateAiSkillVersion(ws, skill.key, version.version, document)
              await refresh()
              setEditing(null)
            }
          }}
        />
      </section>
    )
  }

  return (
    <section aria-label={`Version ${version.version}`} className="space-y-5">
      <div className="space-y-3 rounded-card border border-border bg-surface p-4">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-lg">Version {version.version}</h2>
          <Badge tone={STATUS_TONE[version.status]}>{STATUS_LABEL[version.status]}</Badge>
          {version.status === 'approved' ? <Badge tone="info">Up to {AUTONOMY_LABEL[version.autonomyCeiling].toLowerCase()}</Badge> : null}
          {version.driftFallback ? <Badge tone="warning">Drift: suggests only</Badge> : null}
          {version.status === 'approved' ? <Badge tone={version.evaluationGate.passing ? 'success' : 'warning'}>{version.evaluationGate.passing ? 'Evaluation passed within 30 days' : 'No passing evaluation within 30 days'}</Badge> : null}
        </div>
        <dl className="grid gap-x-6 gap-y-1 text-sm md:grid-cols-2">
          <div><dt className="inline text-muted-foreground">Author: </dt><dd className="inline">{author ? 'you' : 'another person'}</dd></div>
          <div><dt className="inline text-muted-foreground">Model profile: </dt><dd className="inline">{version.modelProfile}</dd></div>
          <div><dt className="inline text-muted-foreground">Submitted: </dt><dd className="inline">{version.submittedAt ? formatDateTime(version.submittedAt) : 'not yet'}</dd></div>
          <div><dt className="inline text-muted-foreground">Approved: </dt><dd className="inline">{version.approvedAt ? `${formatDateTime(version.approvedAt)}${version.approverId === me.subject.id ? ' by you' : ''}` : 'not yet'}</dd></div>
          <div className="md:col-span-2"><dt className="inline text-muted-foreground">Content hash: </dt><dd className="inline font-mono text-xs">{version.contentHash}</dd></div>
        </dl>
        <div className="flex flex-wrap gap-2">
          {can('workflow.edit') && editable ? <Button variant="secondary" onClick={() => setEditing('edit')}>Edit draft</Button> : null}
          {can('workflow.edit') && version.status === 'draft' ? (
            <Button variant="primary" loading={submit.pending} onClick={() => void submit.run(() => submitAiSkillVersion(ws, skill.key, version.version), refresh)}>Submit for approval</Button>
          ) : null}
          {can('ai.approve-skill') && version.status === 'submitted' && !author ? <Button variant="primary" onClick={() => setApproving(true)}>Approve version</Button> : null}
          {can('workflow.edit') && !inProgress ? <Button variant="secondary" onClick={() => setEditing('new')}>New version</Button> : null}
          {can('workflow.edit') && version.status !== 'retired' ? <Button variant="danger" onClick={() => setRetiring(true)}>Retire version</Button> : null}
        </div>
        {version.status === 'submitted' && can('ai.approve-skill') && author ? (
          <Alert tone="info" data-testid="self-approval-hint">You wrote this version, so you cannot approve it. Someone else who may approve AI skills has to approve it (four eyes).</Alert>
        ) : null}
        {version.status === 'submitted' && !can('ai.approve-skill') ? (
          <p className="text-sm text-muted-foreground" data-testid="approve-hint">Waiting for someone who holds the ai.approve-skill permission to approve it.</p>
        ) : null}
        {can('workflow.edit') && inProgress && version.status === 'approved' ? <p className="text-xs text-muted-foreground">A version is in progress already; change that one instead of adding another.</p> : null}
        {submit.error ? <AiProblem error={submit.error} action="submit this version" /> : null}
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList aria-label="Version sections">
          <TabsTrigger value="document">Document</TabsTrigger>
          <TabsTrigger value="evaluations">Evaluations</TabsTrigger>
          <TabsTrigger value="shadow">Shadow and drift</TabsTrigger>
          <TabsTrigger value="promotions">Promotions</TabsTrigger>
        </TabsList>
        <TabsContent value="document"><DocumentView version={version} /></TabsContent>
        <TabsContent value="evaluations">{tab === 'evaluations' ? <EvaluationPanel version={version} /> : null}</TabsContent>
        <TabsContent value="shadow">{tab === 'shadow' ? <ShadowPanel version={version} /> : null}</TabsContent>
        <TabsContent value="promotions">{tab === 'promotions' ? <PromotionsPanel skill={skill} version={version} /> : null}</TabsContent>
      </Tabs>

      {approving ? <ApproveDialog skill={skill} version={version} onCancel={() => setApproving(false)} onDone={async () => { setApproving(false); await refresh() }} /> : null}
      {retiring ? <RetireDialog skill={skill} version={version} onCancel={() => setRetiring(false)} onDone={async () => { setRetiring(false); await refresh() }} /> : null}
    </section>
  )
}

function DocumentView({ version }: { version: AiSkillVersion }) {
  const doc = (version.document ?? {}) as Record<string, unknown>
  const labels = (doc.labels ?? doc.outcomes ?? []) as Array<{ key: string; description: string; risk?: string; adverse?: boolean }>
  const input = (doc.input ?? {}) as Record<string, { type: string; classification?: string; required?: boolean }>
  return (
    <div className="space-y-4">
      <p className="whitespace-pre-wrap rounded-control border border-border bg-surface p-3 text-sm">{String(doc.instructions ?? '')}</p>
      {Object.keys(input).length > 0 ? (
        <div>
          <h3 className="text-base font-semibold">Inputs</h3>
          <ul className="text-sm">{Object.entries(input).map(([n, s]) => <li key={n}><span className="font-mono">{n}</span>: {s.type}{s.required ? ', required' : ''}{s.classification ? `, ${s.classification}` : ''}</li>)}</ul>
        </div>
      ) : null}
      {labels.length > 0 ? (
        <div>
          <h3 className="text-base font-semibold">{doc.labels ? 'Labels' : 'Outcomes'}</h3>
          <ul className="text-sm">{labels.map((l) => <li key={l.key}><span className="font-mono">{l.key}</span>: {l.description}{l.risk ? ` (risk ${l.risk})` : ''}{l.adverse ? ' (adverse)' : ''}</li>)}</ul>
        </div>
      ) : null}
      <details>
        <summary className="cursor-pointer text-sm font-semibold">Skill document (JSON)</summary>
        <pre className="mt-2 max-h-96 overflow-auto rounded-control border border-border bg-surface-muted p-3 text-xs">{JSON.stringify(doc, null, 2)}</pre>
      </details>
    </div>
  )
}

function ApproveDialog({ skill, version, onCancel, onDone }: { skill: AiSkill; version: AiSkillVersion; onCancel: () => void; onDone: () => Promise<void> }) {
  const { workspace } = useWorkspace()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const action = useAction()
  const confirm = () => {
    if (!reason.trim()) return setMissing(true)
    void action.run(() => approveAiSkillVersion(workspace.id, skill.key, version.version, reason.trim()), onDone)
  }
  return (
    <ConfirmDialog open title={`Approve version ${version.version} of ${skill.name}?`} confirmLabel="Approve" pending={action.pending} onConfirm={confirm} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="approve this version" /> : null}>
      <p>Approved versions never change. New publishes pin the highest approved version; AI tasks still need a passing evaluation to decide outside shadow mode.</p>
      <FormField label="Reason" required error={missing ? 'Say why this skill version may be used.' : undefined}>
        {(ids) => <Textarea id={ids.input} aria-describedby={ids.describedBy} aria-invalid={missing ? true : undefined} value={reason} maxLength={1000} onChange={(e) => { setReason(e.target.value); setMissing(false) }} />}
      </FormField>
    </ConfirmDialog>
  )
}

function RetireDialog({ skill, version, onCancel, onDone }: { skill: AiSkill; version: AiSkillVersion; onCancel: () => void; onDone: () => Promise<void> }) {
  const { workspace } = useWorkspace()
  const action = useAction()
  return (
    <ConfirmDialog open title={`Retire version ${version.version} of ${skill.name}?`} confirmLabel="Retire" destructive pending={action.pending}
      onConfirm={() => void action.run(() => retireAiSkillVersion(workspace.id, skill.key, version.version), onDone)} onCancel={onCancel}
      error={action.error ? <AiProblem error={action.error} action="retire this version" /> : null}>
      <p>This is the kill switch: AI tasks pinned to this version stop asking the model and take their aiUnavailable path. No new publish can pin it. It cannot be undone; add a new version instead.</p>
    </ConfirmDialog>
  )
}
