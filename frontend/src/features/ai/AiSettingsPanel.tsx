import { Sparkles } from 'lucide-react'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { QueryError } from '@/components/common/QueryError'
import { Badge } from '@/components/ui/badge'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { formatUsd, useAiStatus } from './api'

/** Whether Settings shows the AI tab: AI is on, or it is configured but currently unavailable (reason shown). */
export function useShowAiSettings(): boolean {
  const status = useAiStatus()
  return !!status.data && (status.data.enabled || status.data.profiles.length > 0)
}

/** Settings → AI (S10 section 6): provider, profiles, excluded models, price caps and month-to-date spend. Read-only. */
export function AiSettingsPanel() {
  const status = useAiStatus()
  if (status.isPending) return <LoadingBlock rows={3} label="Loading AI settings" />
  if (status.isError) return <QueryError error={status.error} onRetry={() => void status.refetch()} what="the AI settings" />
  const data = status.data
  const monthly = data.budget?.monthlyUsd ?? 0
  const spent = data.budget?.spentUsd ?? 0
  const share = monthly > 0 ? Math.min(100, Math.round((spent / monthly) * 100)) : 0

  return (
    <div className="space-y-6" data-testid="ai-settings">
      <section className="rounded-card border border-border bg-surface p-4" aria-labelledby="ai-state">
        <div className="flex flex-wrap items-center gap-3">
          <Sparkles className="size-5 text-primary-text" aria-hidden="true" />
          <h2 id="ai-state" className="text-base">
            AI assistance
          </h2>
          {data.enabled ? <Badge tone="success">On</Badge> : <Badge tone="warning">Off</Badge>}
        </div>
        <p className="mt-2 text-sm text-muted-foreground">
          {data.enabled
            ? 'Designers can draft workflows from a description and anyone who can read a workflow can ask for a plain-language explanation. AI never publishes or runs anything.'
            : (data.reason ?? 'AI assistance is not available.')}
        </p>
        <p className="mt-2 text-sm text-muted-foreground">Provider: OpenRouter. These settings belong to the deployment and are read-only here.</p>
      </section>

      <section aria-labelledby="ai-spend" className="rounded-card border border-border bg-surface p-4">
        <h3 id="ai-spend" className="text-sm">
          Spend this month
        </h3>
        <p className="mt-1 text-sm">
          {formatUsd(spent)} of {formatUsd(monthly)} ({share}%)
        </p>
        <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-neutral-soft" role="img" aria-label={`${share}% of the monthly AI budget used`}>
          <div className={share >= 90 ? 'h-full bg-danger' : 'h-full bg-primary'} style={{ width: `${share}%` }} />
        </div>
        <p className="mt-2 text-xs text-muted-foreground">Costs come from the provider&apos;s usage reports. When the budget is used up, AI pauses until next month; templates and the studio keep working.</p>
      </section>

      <section aria-labelledby="ai-profiles">
        <h3 id="ai-profiles" className="mb-2 text-sm">
          Model profiles
        </h3>
        {data.profiles.length === 0 ? (
          <p className="text-sm text-muted-foreground">No profile can be used right now.</p>
        ) : (
          <div className="overflow-hidden rounded-card border border-border bg-surface">
            <Table>
              <caption className="sr-only">AI model profiles</caption>
              <THead>
                <TR>
                  <TH>Profile</TH>
                  <TH>Model</TH>
                  <TH>Price</TH>
                </TR>
              </THead>
              <TBody>
                {data.profiles.map((p) => (
                  <TR key={p.id}>
                    <TD>
                      <span className="font-semibold">{p.label}</span>
                      {p.id === data.defaultProfile ? (
                        <Badge tone="primary" className="ml-2">
                          Default
                        </Badge>
                      ) : null}
                    </TD>
                    <TD className="font-mono text-xs">{p.model}</TD>
                    <TD>{p.variablePrice ? 'Varies per request (router), limited by the budget' : 'Fixed, within the price caps'}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
        )}
      </section>

      <section aria-labelledby="ai-limits" className="grid gap-4 md:grid-cols-2">
        <div className="rounded-card border border-border bg-surface p-4">
          <h3 id="ai-limits" className="text-sm">
            Price caps
          </h3>
          <p className="mt-1 text-sm text-muted-foreground">
            Up to USD {(data.maxPrice?.promptPerMillion ?? 0).toFixed(2)} per million input tokens and USD {(data.maxPrice?.completionPerMillion ?? 0).toFixed(2)} per million output tokens.
          </p>
        </div>
        <div className="rounded-card border border-border bg-surface p-4">
          <h3 className="text-sm">Excluded models</h3>
          {data.excludedModels && data.excludedModels.length > 0 ? (
            <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Excluded models">
              {data.excludedModels.map((m) => (
                <li key={m}>
                  <Badge className="font-mono">{m}</Badge>
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">None.</p>
          )}
        </div>
      </section>
    </div>
  )
}
