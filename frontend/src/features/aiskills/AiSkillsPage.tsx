import { useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useWorkspace } from '@/app/workspace'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { PageHeader } from '@/components/common/PageHeader'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { aiSkillKeys, AUTONOMY_LABEL, createAiSkill, MODE_LABEL, STATUS_LABEL, STATUS_TONE, useAiSkills, type AiSkill } from './api'
import { EvalSuitesPanel } from './EvalSuitesPanel'
import { SkillEditor } from './SkillEditor'

/**
 * AI skills in the Design area (docs/build/S146 part D over the S134 API): the shared, versioned skills that AI task
 * steps use, with their approval state, plus the evaluation suites. Designers (workflow.edit) author; a different person
 * holding ai.approve-skill approves; evaluators (ai.evaluate) run evaluations.
 */
export function AiSkillsPage() {
  const { workspace, can } = useWorkspace()
  const ws = workspace.id
  const readable = can('workflow.read', 'ai.evaluate', 'ai.approve-skill')
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'suites' ? 'suites' : 'skills'
  const creating = params.get('new') === '1' && can('workflow.edit')
  const skills = useAiSkills(ws, readable)
  const client = useQueryClient()
  const navigate = useNavigate()

  if (!readable) {
    return <Alert tone="warning">You need permission to read workflows, run evaluations or approve AI skills to see AI skills.</Alert>
  }
  const setTab = (next: string) => setParams(next === 'suites' ? { tab: 'suites' } : {}, { replace: true })

  return (
    <div className="space-y-6">
      <PageHeader
        title="AI skills"
        description="Shared, versioned instructions for AI task steps. A version is used only after another person approves it and an evaluation passes."
        actions={<>
          {can('workflow.edit') && !creating ? <Button variant="primary" onClick={() => setParams({ new: '1' })}><Plus aria-hidden="true" /> New skill</Button> : null}
          <Link className="text-link underline" to={`/w/${ws}/ai-policy`}>Workspace AI policy</Link>
        </>}
      />
      {creating ? (
        <section aria-labelledby="new-skill-heading" className="space-y-3">
          <h2 id="new-skill-heading">New AI skill</h2>
          <p className="text-sm text-muted-foreground">Saving creates version 1 as a draft. Submit it for approval when it is ready.</p>
          <SkillEditor
            initial={null}
            keyLocked={false}
            saveLabel="Create draft"
            onCancel={() => setParams({})}
            onSave={async (document) => {
              const created = await createAiSkill(ws, document)
              await client.invalidateQueries({ queryKey: aiSkillKeys.list(ws) })
              void navigate(`/w/${ws}/ai-skills/${created.key}`)
            }}
          />
        </section>
      ) : null}
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList aria-label="AI skills sections">
          <TabsTrigger value="skills">Skills</TabsTrigger>
          <TabsTrigger value="suites">Evaluation suites</TabsTrigger>
        </TabsList>
        <TabsContent value="skills">
          {skills.isPending ? <LoadingBlock rows={3} label="Loading AI skills" />
            : skills.isError ? <QueryError error={skills.error} onRetry={() => void skills.refetch()} what="the AI skills" />
              : skills.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No AI skill yet.{can('workflow.edit') ? ' Create one with New skill.' : ''}</p>
                : <SkillTable ws={ws} items={skills.data.items} />}
        </TabsContent>
        <TabsContent value="suites">
          <EvalSuitesPanel skills={skills.data?.items ?? []} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function SkillTable({ ws, items }: { ws: string; items: AiSkill[] }) {
  return (
    <div className="overflow-x-auto rounded-card border border-border bg-surface">
      <Table>
        <caption className="sr-only">AI skills</caption>
        <THead>
          <TR><TH>Skill</TH><TH>Mode</TH><TH>Versions</TH><TH>In use</TH></TR>
        </THead>
        <TBody>
          {items.map((s) => {
            const latest = [...s.versions].sort((a, b) => b.version - a.version)[0]
            const approved = s.versions.find((v) => v.version === s.approvedVersion)
            return (
              <TR key={s.key}>
                <TD>
                  <Link to={`/w/${ws}/ai-skills/${s.key}`} className="font-semibold">{s.name}</Link>
                  <span className="block font-mono text-xs text-muted-foreground">{s.key}</span>
                </TD>
                <TD>{MODE_LABEL[s.mode]}</TD>
                <TD>
                  {latest ? <span className="flex flex-wrap items-center gap-2">Version {latest.version} <Badge tone={STATUS_TONE[latest.status]}>{STATUS_LABEL[latest.status]}</Badge></span> : 'None'}
                </TD>
                <TD>
                  {approved ? (
                    <span className="flex flex-wrap items-center gap-2">
                      Version {approved.version}, up to {AUTONOMY_LABEL[approved.autonomyCeiling].toLowerCase()}
                      {approved.driftFallback ? <Badge tone="warning">Drift: suggests only</Badge> : null}
                      {!approved.evaluationGate.passing ? <Badge tone="warning">No passing evaluation</Badge> : null}
                    </span>
                  ) : <span className="text-muted-foreground">No approved version</span>}
                </TD>
              </TR>
            )
          })}
        </TBody>
      </Table>
    </div>
  )
}
