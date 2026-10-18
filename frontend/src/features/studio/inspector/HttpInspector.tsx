import { useMemo } from 'react'
import { useConnection, useConnections } from '@/api/queries'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { EFFECT_CLASS_LABEL } from '@/features/runs/operatorText'
import { AGENT_USES_AI, AgentSpecView, type AgentSpec } from '@/features/settings/AgentSpecView'
import { humanize } from '@/lib/text'
import { useStudio, useStudioContext } from '../StudioContext'
import { availableFields, chipLabel, type FieldInfo } from '../model/fieldRefs'
import { renameHttpResult, syncConnectionDependencies } from '../model/ops'
import { connectionDefinition } from '../connectionSchema'
import { cfg, type WorkflowNode } from '../model/types'
import { Advanced, CommitInput, CommitNumber, InspectorSection } from './controls'
import { UndoFields } from './ScopeInspectors'
import { StepIdField } from './SimpleInspectors'
import { DocumentReferenceAuthoring } from './DocumentReferenceAuthoring'
import { applyDocumentReference } from '../model/documentReference'
import type { DocumentOperation } from '@/features/settings/DocumentVersionPicker'

interface ScalarSchema {
  type: 'string' | 'number' | 'integer' | 'boolean'
  description?: string
  enum?: string[]
}

interface OperationDoc {
  testable?: boolean
  path?: string
  label: string
  description?: string
  requestSchema?: { required?: string[]; properties: Record<string, ScalarSchema> }
  responseSchema: { properties?: Record<string, ScalarSchema> }
  effectClass: keyof typeof EFFECT_CLASS_LABEL
  maxTimeoutSeconds: number
  /** Present on operations of an agent connection (S13). */
  agent?: AgentSpec
}

function compatible(source: FieldInfo['type'], target: ScalarSchema['type']): boolean {
  if (target === 'string') return source === 'string' || source === 'date'
  if (target === 'boolean') return source === 'boolean'
  if (target === 'integer') return source === 'integer'
  return source === 'number' || source === 'integer'
}

/** Call a system: connection, operation, input mapping and result fields (docs/build/S7-frontend.md section 5). */
export function HttpInspector({ node }: { node: WorkflowNode }) {
  const { can } = useWorkspace()
  const { store, wsId, readOnly, connections: lookup } = useStudioContext()
  const def = useStudio((s) => s.definition)
  const connectionKey = cfg<string>(node, 'connectionRef') ?? ''
  const operationName = cfg<string>(node, 'operation') ?? ''
  const inputMapping = useMemo(() => (cfg<Record<string, string>>(node, 'inputMapping') ?? {}) as Record<string, string>, [node])
  const resultMapping = useMemo(() => (cfg<Record<string, string>>(node, 'resultMapping') ?? {}) as Record<string, string>, [node])

  const list = useConnections(wsId)
  const detail = useConnection(wsId, connectionKey || null)
  const summary = list.data?.items.find((c) => c.key === connectionKey)
  const pinned = def.dependencies.connections.find((c) => c.key === connectionKey)?.definitionVersion
  const record = connectionDefinition(detail.data, pinned)
  const operationDocs = record?.definition.operations as Record<string, OperationDoc> | undefined
  const operation = (record?.definition.operations as Record<string, OperationDoc> | undefined)?.[operationName]
  const available = useMemo(() => availableFields(def, node.id, lookup), [def, node.id, lookup])

  const patch = (p: Record<string, unknown>) => store.getState().updateNodeConfig(node.id, p)

  const chooseConnection = (key: string) => {
    const latest = list.data?.items.find((c) => c.key === key)?.latestVersion
    store.getState().apply((d) => {
      const cleared = {
        ...d,
        nodes: d.nodes.map((n) => (n.id === node.id ? { ...n, config: { ...n.config, connectionRef: key, operation: '', inputMapping: {}, resultMapping: {} } } : n)),
      }
      return syncConnectionDependencies(cleared, latest ? { [key]: latest } : {})
    })
  }

  const chooseOperation = (name: string) => {
    const doc = (record?.definition.operations as Record<string, OperationDoc> | undefined)?.[name]
    const timeout = cfg<number>(node, 'timeoutSeconds') ?? 10
    patch({ operation: name, inputMapping: {}, resultMapping: {}, timeoutSeconds: doc ? Math.min(timeout, doc.maxTimeoutSeconds) : timeout })
  }

  const setMapping = (target: string, ref: string) => {
    const next = { ...inputMapping }
    if (ref) next[target] = ref
    else delete next[target]
    patch({ inputMapping: next })
  }

  const responseProps = Object.entries(operation?.responseSchema.properties ?? {})
  const resultByPointer = new Map(Object.entries(resultMapping).map(([name, pointer]) => [pointer, name]))

  const toggleResult = (prop: string, keep: boolean) => {
    const next = { ...resultMapping }
    const pointer = `/${prop}`
    if (keep) {
      let name = prop
      for (let i = 2; name in next; i++) name = `${prop}${i}`
      next[name] = pointer
    } else {
      for (const [name, p] of Object.entries(next)) if (p === pointer) delete next[name]
    }
    patch({ resultMapping: next })
  }

  const renameResult = (pointer: string, name: string) => {
    const oldName = resultByPointer.get(pointer)
    if (!oldName) return
    store.getState().apply((d) => {
      const result = renameHttpResult(d, node.id, oldName, name)
      return result.ok ? result.def : d
    })
  }

  return (
    <>
      <InspectorSection title="Basic">
        <FormField label="Connection" required id={`http-conn-${node.id}`} description="Only approved connections can be used.">
          {(ids) => (
            <Select id={ids.input} data-field="config.connectionRef" value={connectionKey} disabled={readOnly || list.isPending} aria-describedby={ids.describedBy} onChange={(e) => chooseConnection(e.target.value)}>
              <option value="">{list.isPending ? 'Loading connections…' : 'Choose a connection'}</option>
              {connectionKey && !summary && !list.isPending ? <option value={connectionKey}>{connectionKey} (not found)</option> : null}
              {(list.data?.items ?? []).map((c) => (
                <option key={c.key} value={c.key}>
                  {c.name}
                  {c.kind === 'agent' ? ' · AI agent' : ''}
                  {!c.binding.configured ? ' (no address configured)' : !c.binding.enabled ? ' (disabled)' : ''}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        {summary ? (
          <p className="text-xs text-muted-foreground">
            Uses version {pinned ?? summary.latestVersion}
            {summary.binding.host ? ` · sends to ${summary.binding.host}` : ' · no address configured in this workspace'}
          </p>
        ) : null}
        {list.isError ? <QueryError error={list.error} onRetry={() => void list.refetch()} what="connections" /> : null}
        {connectionKey && detail.isError ? <QueryError error={detail.error} onRetry={() => void detail.refetch()} what="the connection definition" /> : null}
        {connectionKey && detail.isSuccess && !record ? <Alert tone="warning">Connection version {pinned} is unavailable. Restore this version before editing its mappings.</Alert> : null}
        {summary && (!summary.binding.configured || !summary.binding.enabled) ? (
          <Alert tone="warning">{!summary.binding.configured ? 'This connection needs an address in workspace Settings before it can run.' : 'This connection is disabled. Ask a workspace administrator to enable it before running the workflow.'}</Alert>
        ) : null}

        <FormField label="Operation" required id={`http-op-${node.id}`}>
          {(ids) => (
            <Select id={ids.input} data-field="config.operation" value={operationName} disabled={readOnly || !record} aria-describedby={ids.describedBy} onChange={(e) => chooseOperation(e.target.value)}>
              <option value="">{connectionKey ? 'Choose an operation' : 'Choose a connection first'}</option>
              {operationName && !operation && !detail.isPending ? <option value={operationName}>{operationName} (not found)</option> : null}
              {Object.entries(operationDocs ?? {}).map(([name, o]) => (
                <option key={name} value={name}>
                  {o.label}
                </option>
              ))}
            </Select>
          )}
        </FormField>
        {operation?.description ? <p className="text-xs text-muted-foreground">{operation.description}</p> : null}
        {operation?.agent ? <AgentSpecView operation={operation} /> : null}
        {summary && record?.definition.kind === 'microsoftGraph' && operation && ['/text', '/extract', '/extractSnapshot'].includes(operation.path ?? '') ? <DocumentReferenceAuthoring
          key={`${node.id}:${connectionKey}:${record.version}:${operationName}`}
          workspaceId={wsId} connectionKey={connectionKey} connectionVersion={record.version} path={operation.path!}
          operations={operationDocs as Record<string, DocumentOperation>}
          testableOperations={Object.entries(operationDocs ?? {}).filter(([, entry]) => entry.testable).map(([name]) => name)}
          approvedDrives={operation.requestSchema?.properties.driveId?.enum ?? []}
          configuredReference={Object.fromEntries(['driveId', 'itemId', 'versionId', 'snapshotId'].flatMap((field) => {
            const ref = inputMapping[field]
            const question = ref?.startsWith('/input/') ? def.inputSchema.properties[ref.slice(7)] : undefined
            return question?.type === 'string' && question.enum?.length === 1 ? [[field, question.enum[0]]] : []
          }))}
          canManage={can('connection.manage')} enabled={summary.binding.configured && summary.binding.enabled} readOnly={readOnly}
          onApply={(reference) => {
            const result = applyDocumentReference(store.getState().definition, node.id, reference, Object.keys(operation.responseSchema.properties ?? {}))
            if (!result.ok) return result.reason
            store.getState().apply(() => result.definition)
            return undefined
          }}
        /> : null}

        {operation?.requestSchema ? (
          <fieldset className="space-y-3" data-field="config.inputMapping">
            <legend className="mb-1 text-sm font-semibold">What to send</legend>
            {Object.entries(operation.requestSchema.properties).map(([target, schema]) => {
              const required = (operation.requestSchema?.required ?? []).includes(target)
              const options = available.filter((f) => compatible(f.type, schema.type))
              const current = inputMapping[target] ?? ''
              const currentKnown = !current || options.some((o) => o.ref === current)
              return (
                <div key={target} className="space-y-1.5 rounded-control border border-border bg-surface-muted p-2.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <Label htmlFor={`map-${node.id}-${target}`}>
                      {humanize(target)} <span className="font-normal text-muted-foreground">({schema.type})</span>
                    </Label>
                    {required ? <Badge tone="warning">required</Badge> : <Badge>optional</Badge>}
                  </div>
                  <Select
                    id={`map-${node.id}-${target}`}
                    data-field={`config.inputMapping.${target}`}
                    value={current}
                    disabled={readOnly}
                    aria-invalid={required && !current ? true : undefined}
                    onChange={(e) => setMapping(target, e.target.value)}
                  >
                    <option value="">{required ? 'Choose a value to send' : 'Do not send'}</option>
                    {!currentKnown ? <option value={current}>{current} (not available here)</option> : null}
                    {options.map((f) => (
                      <option key={f.ref} value={f.ref}>
                        {chipLabel(f)}
                      </option>
                    ))}
                  </Select>
                </div>
              )
            })}
          </fieldset>
        ) : null}

        {responseProps.length > 0 ? (
          <fieldset className="space-y-2" data-field="config.resultMapping">
            <legend className="mb-1 text-sm font-semibold">Result fields to keep</legend>
            <p className="text-xs text-muted-foreground">Later steps and the workflow output can use the fields you keep.</p>
            {responseProps.map(([prop]) => {
              const pointer = `/${prop}`
              const keptAs = resultByPointer.get(pointer)
              return (
                <div key={prop} className="flex flex-wrap items-center gap-3 rounded-control border border-border bg-surface-muted p-2.5">
                  <Checkbox id={`keep-${node.id}-${prop}`} checked={!!keptAs} disabled={readOnly} onCheckedChange={(v) => toggleResult(prop, v === true)} />
                  <Label htmlFor={`keep-${node.id}-${prop}`} className="font-medium">
                    Keep &ldquo;{prop}&rdquo;
                  </Label>
                  {keptAs ? (
                    <div className="flex w-full items-center gap-2">
                      <Label htmlFor={`keepname-${node.id}-${prop}`} className="shrink-0 font-normal">
                        as
                      </Label>
                      <CommitInput id={`keepname-${node.id}-${prop}`} value={keptAs} disabled={readOnly} spellCheck={false} onCommit={(v) => renameResult(pointer, v.trim())} />
                    </div>
                  ) : null}
                </div>
              )
            })}
          </fieldset>
        ) : null}
      </InspectorSection>

      <UndoFields node={node} />

      <Advanced>
        <FormField label="Timeout (seconds)" id={`http-timeout-${node.id}`} description={operation ? `At most ${operation.maxTimeoutSeconds} seconds for this operation.` : undefined}>
          {(ids) => (
            <CommitNumber
              id={ids.input}
              data-field="config.timeoutSeconds"
              integer
              value={cfg<number>(node, 'timeoutSeconds')}
              disabled={readOnly}
              aria-describedby={ids.describedBy}
              onCommit={(v) => patch({ timeoutSeconds: v === undefined ? undefined : Math.max(1, Math.min(operation?.maxTimeoutSeconds ?? 30, v)) })}
            />
          )}
        </FormField>
        {operation ? (
          <div>
            <p className="text-sm font-semibold">Retry behavior</p>
            <p className="text-sm text-muted-foreground" data-testid="effect-class">
              {EFFECT_CLASS_LABEL[operation.effectClass]}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">Set by the connection, not by the workflow.{operation.agent ? ` ${AGENT_USES_AI}.` : ''}</p>
          </div>
        ) : null}
        <StepIdField node={node} />
      </Advanced>
    </>
  )
}
