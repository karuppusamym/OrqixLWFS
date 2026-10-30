import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { WorkspaceFeature, WorkspaceFeatureState } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { useAnnounce } from '@/components/common/Announcer'
import { LoadingBlock } from '@/components/common/LoadingBlock'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { QueryError } from '@/components/common/QueryError'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/dates'
import { FEATURES, featureInfo } from '@/lib/features'

const featuresKey = (ws: string) => ['ws', ws, 'features'] as const

/**
 * Settings → Features (docs/build/S127): a workspace administrator switches product modules on or off. Switching off
 * hides the module and makes the server refuse new authoring and new starts in it; nothing is deleted and work that is
 * already running keeps running. Every change is audited.
 */
export function FeaturesPanel() {
  const { workspace } = useWorkspace()
  const ws = workspace.id
  const query = useQuery({ queryKey: featuresKey(ws), queryFn: () => endpoints.getWorkspaceFeatures(ws) })
  return (
    <section aria-labelledby="features-heading" className="max-w-3xl space-y-4">
      <div>
        <h2 id="features-heading">Features</h2>
        <p className="text-sm text-muted-foreground">
          Choose which parts of Orqix people can use in this workspace. Switching a part off hides it and stops anyone from
          creating or starting something new in it. Nothing is deleted, and work that is already running keeps running.
          Every change is recorded in the audit log.
        </p>
      </div>
      {query.isPending ? (
        <LoadingBlock rows={3} label="Loading features" />
      ) : query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} what="the features" />
      ) : (
        <ul className="space-y-3">
          {FEATURES.map((info) => {
            const state = query.data.items.find((s) => s.feature === info.feature)
            return state ? <FeatureRow key={info.feature} ws={ws} state={state} /> : null
          })}
        </ul>
      )}
    </section>
  )
}

function FeatureRow({ ws, state }: { ws: string; state: WorkspaceFeatureState }) {
  const client = useQueryClient()
  const announce = useAnnounce()
  const info = featureInfo(state.feature)
  const [confirming, setConfirming] = useState(false)
  const headingId = `feature-${state.feature}`
  const change = useMutation({
    mutationFn: (enabled: boolean) => endpoints.setWorkspaceFeature(ws, state.feature as WorkspaceFeature, enabled),
    onSuccess: (next, enabled) => {
      setConfirming(false)
      client.setQueryData(featuresKey(ws), next)
      // The menu, Home and every module page read the switches from /me; AI reads them from its status.
      void client.invalidateQueries()
      announce(`${info.name} is now ${enabled ? 'on' : 'off'}.`)
    },
  })

  return (
    <li className="rounded-card border border-border bg-surface p-4" data-testid={`feature-${state.feature}`} aria-labelledby={headingId}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <h3 id={headingId} className="flex items-center gap-2 text-base font-semibold">
            {info.name}
            <Badge tone={state.enabled ? 'success' : 'neutral'} data-testid={`feature-${state.feature}-state`}>{state.enabled ? 'On' : 'Off'}</Badge>
          </h3>
          <p className="text-sm">{info.covers}</p>
          {state.updatedAt ? (
            <p className="text-xs text-muted-foreground">
              Switched {state.enabled ? 'on' : 'off'} by {state.updatedBy?.displayName ?? 'someone'} on {formatDateTime(state.updatedAt)}
            </p>
          ) : null}
        </div>
        {state.enabled ? (
          !confirming ? (
            <Button variant="danger" onClick={() => setConfirming(true)} aria-describedby={headingId}>
              Switch off
            </Button>
          ) : null
        ) : (
          <Button variant="primary" onClick={() => change.mutate(true)} disabled={change.isPending} aria-describedby={headingId}>
            {change.isPending ? 'Switching on…' : 'Switch on'}
          </Button>
        )}
      </div>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="font-semibold">While it is off</dt>
          <dd className="text-muted-foreground">{info.whenOff}</dd>
        </div>
        <div>
          <dt className="font-semibold">Work already running</dt>
          <dd className="text-muted-foreground">{info.running}</dd>
        </div>
      </dl>
      {confirming ? (
        <Alert tone="warning" className="mt-3" title={`Switch off ${info.name}?`} live="off">
          <p>{info.whenOff} {info.running}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="danger-solid" onClick={() => change.mutate(false)} disabled={change.isPending}>
              {change.isPending ? 'Switching off…' : `Switch off ${info.name}`}
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={change.isPending}>Cancel</Button>
          </div>
        </Alert>
      ) : null}
      {change.isError ? <ProblemAlert error={toProblem(change.error)} className="mt-3" context={{ action: `switch ${info.name} ${state.enabled ? 'off' : 'on'}` }} /> : null}
    </li>
  )
}
