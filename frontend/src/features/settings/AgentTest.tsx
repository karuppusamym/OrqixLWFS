import { CheckCircle2, ServerCrash } from 'lucide-react'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { useConnection } from '@/api/queries'
import type { ConnectionSummary, ConnectionTestResult } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { FormField } from '@/components/common/FormField'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/input'
import { useAiStatus } from '@/features/ai/api'
import { humanize } from '@/lib/text'
import { AGENT_USES_AI, AgentSpecView, type AgentOperationDoc } from './AgentSpecView'

interface Field {
  type: 'string' | 'number' | 'integer' | 'boolean'
  description?: string
  enum?: string[]
}

interface OperationDoc extends AgentOperationDoc {
  requestSchema?: { required?: string[]; properties: Record<string, Field> }
}

/**
 * Test connection for an agent: shows what the agent may do, asks for a sample input and runs it once. The call uses AI
 * and is metered like any AI use, so it is offered only when AI is enabled (docs/build/S13-agents-mcp.md section 8).
 */
export function AgentTest({ connection }: { connection: ConnectionSummary }) {
  const { workspace } = useWorkspace()
  const detail = useConnection(workspace.id, connection.key)
  const ai = useAiStatus()
  const testable = connection.operations.filter((o) => o.testable)
  const [operation, setOperation] = useState(testable[0]?.name ?? '')
  const [values, setValues] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<ConnectionTestResult | null>(null)
  const [error, setError] = useState<ProblemError | null>(null)

  if (detail.isPending) return <LoadingBlock rows={1} label="Loading the agent" />
  const record = detail.data?.definitions.find((d) => d.version === connection.latestVersion)
  const doc = (record?.definition.operations as Record<string, OperationDoc> | undefined)?.[operation]
  const properties = Object.entries(doc?.requestSchema?.properties ?? {})
  const required = doc?.requestSchema?.required ?? []
  const aiOff = ai.data ? !ai.data.enabled : false
  const bindingOk = connection.binding.configured && connection.binding.enabled
  const missing = required.some((name) => !(values[name] ?? '').trim())

  const sample = (): Record<string, string | number | boolean> => {
    const input: Record<string, string | number | boolean> = {}
    for (const [name, field] of properties) {
      const raw = (values[name] ?? '').trim()
      if (!raw) continue
      input[name] = field.type === 'number' || field.type === 'integer' ? Number(raw) : field.type === 'boolean' ? raw === 'true' : raw
    }
    return input
  }

  const run = async () => {
    setPending(true)
    setError(null)
    setResult(null)
    try {
      setResult(await endpoints.testConnection(workspace.id, connection.key, connection.latestVersion, operation, sample() as Record<string, string>))
    } catch (cause) {
      setError(toProblem(cause))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="space-y-4" data-testid={`agent-test-${connection.key}`}>
      <h3>Test the agent</h3>
      {doc ? <AgentSpecView operation={doc} /> : null}
      {testable.length === 0 ? (
        <p className="text-sm text-muted-foreground">None of this agent&apos;s operations can be tested.</p>
      ) : (
        <>
          <p className="text-sm text-muted-foreground">
            Runs the operation once with the sample below, through the real address and the network policy. {AGENT_USES_AI}: the test is metered like any AI use and nothing is changed in the other system.
          </p>
          {testable.length > 1 ? (
            <FormField label="Operation to test" id={`agent-op-${connection.key}`}>
              {(ids) => (
                <Select id={ids.input} value={operation} onChange={(e) => { setOperation(e.target.value); setResult(null) }}>
                  {testable.map((o) => (
                    <option key={o.name} value={o.name}>
                      {o.label}
                    </option>
                  ))}
                </Select>
              )}
            </FormField>
          ) : null}
          {properties.map(([name, field]) => (
            <FormField key={name} label={humanize(name)} required={required.includes(name)} id={`agent-${connection.key}-${name}`} description={field.description}>
              {(ids) =>
                field.enum ? (
                  <Select id={ids.input} value={values[name] ?? ''} aria-describedby={ids.describedBy} onChange={(e) => setValues({ ...values, [name]: e.target.value })}>
                    <option value="">Choose a value</option>
                    {field.enum.map((v) => (
                      <option key={v} value={v}>
                        {v}
                      </option>
                    ))}
                  </Select>
                ) : (
                  <Input
                    id={ids.input}
                    type={field.type === 'number' || field.type === 'integer' ? 'number' : 'text'}
                    value={values[name] ?? ''}
                    aria-describedby={ids.describedBy}
                    autoComplete="off"
                    onChange={(e) => setValues({ ...values, [name]: e.target.value })}
                  />
                )
              }
            </FormField>
          ))}
          {aiOff ? <Alert tone="warning" title="AI is not available">{ai.data?.reason ?? 'AI is not configured in this environment.'} An administrator must fix it before an agent can be tested.</Alert> : null}
          {!bindingOk ? <p className="text-sm text-muted-foreground">Save an enabled tool server address before testing.</p> : null}
          <Button variant="secondary" onClick={() => void run()} loading={pending} disabled={aiOff || !bindingOk || missing || !doc}>
            Test the agent
          </Button>
        </>
      )}
      <div aria-live="polite" role="status">
        {result ? (
          result.outcome === 'succeeded' ? (
            <Alert tone="success" live="off" title="The agent answered">
              <span className="inline-flex items-center gap-1.5" data-testid="agent-test-summary">
                <CheckCircle2 className="size-4" aria-hidden="true" /> {result.summary}
              </span>
              <dl className="mt-2 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5" data-testid="agent-test-output">
                {Object.entries((result.output ?? {}) as Record<string, unknown>).map(([k, v]) => (
                  <div key={k} className="contents">
                    <dt className="font-medium">{humanize(k)}</dt>
                    <dd>{String(v)}</dd>
                  </div>
                ))}
              </dl>
              <span className="mt-2 block text-xs">{(result.durationMs / 1000).toFixed(1)} s. {result.detail}</span>
            </Alert>
          ) : (
            <Alert tone="danger" live="off" title="The test failed">
              <span className="inline-flex items-center gap-1.5">
                <ServerCrash className="size-4" aria-hidden="true" /> {result.errorCode ?? 'Failed'} after {(result.durationMs / 1000).toFixed(1)} s
              </span>
              {result.detail ? <span className="mt-1 block">{result.detail}</span> : null}
            </Alert>
          )
        ) : null}
      </div>
      {error ? <ProblemAlert error={error} /> : null}
    </div>
  )
}
