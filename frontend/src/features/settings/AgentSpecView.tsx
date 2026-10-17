import { Sparkles } from 'lucide-react'
import { Badge } from '@/components/ui/badge'

/** The agent part of an operation of an `agent` connection (connection-definition-v0.schema.json, agentSpec). */
export interface AgentSpec {
  instructions: string
  modelProfile?: string
  tools: Array<{ name: string; label: string; description?: string }>
  maxToolCalls: number
  summary?: string
}

export interface AgentOperationDoc {
  label: string
  description?: string
  agent?: AgentSpec
}

/** Badge shown wherever an agent connection is listed or chosen. */
export function AiAgentBadge() {
  return (
    <Badge tone="info" data-testid="ai-agent-badge">
      <Sparkles aria-hidden="true" /> AI agent
    </Badge>
  )
}

/** Cost and safety line shown wherever a step or connection uses an agent. */
export const AGENT_USES_AI = 'Uses AI (cost varies, read-only)'

/**
 * Read-only view of what an agent operation may do: its fixed instructions, the tools it may call and its limits.
 * Instructions are written by an administrator and versioned with the connection; they are never editable in a workflow.
 */
export function AgentSpecView({ operation }: { operation: AgentOperationDoc }) {
  const agent = operation.agent
  if (!agent) return null
  return (
    <div className="space-y-3 rounded-control border border-border bg-surface-muted p-3" data-testid="agent-spec">
      <div className="flex flex-wrap items-center gap-2">
        <AiAgentBadge />
        <span className="text-sm text-muted-foreground">{AGENT_USES_AI}</span>
      </div>
      <div>
        <p className="text-sm font-semibold">Instructions</p>
        <p className="mt-1 whitespace-pre-wrap rounded-control border border-border bg-surface p-2 text-sm text-muted-foreground" data-testid="agent-instructions">
          {agent.instructions}
        </p>
        <p className="mt-1 text-xs text-muted-foreground">Fixed by the connection. A workflow can only send its input and keep the result fields.</p>
      </div>
      <div>
        <p className="text-sm font-semibold">Tools it may use</p>
        <ul className="mt-1 space-y-1 text-sm" data-testid="agent-tools">
          {agent.tools.map((tool) => (
            <li key={tool.name}>
              <span className="font-medium">{tool.label}</span> <span className="font-mono text-xs text-muted-foreground">{tool.name}</span>
            </li>
          ))}
        </ul>
        <p className="mt-1 text-xs text-muted-foreground">
          At most {agent.maxToolCalls} tool {agent.maxToolCalls === 1 ? 'call' : 'calls'} per step. It cannot change anything; it can only produce the result fields.
        </p>
      </div>
    </div>
  )
}
