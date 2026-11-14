import { useQuery } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
import { Link } from 'react-router'
import { api, call } from '@/api/client'
import type { components } from '@/api/schema'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'

type Summary = components['schemas']['AiVerificationSummary']

const STATUS: Record<Summary['status'], string> = {
  open: 'Waiting for a person', verified: 'Verified', escalated: 'Sent for review', cancelled: 'Cancelled',
}

/**
 * Verification tasks of a request's document extractions (CG-A06): each links to the screen that shows the document
 * beside the extracted fields. Hidden when the request has none.
 */
export function DocumentVerificationsPanel({ ws, runId }: { ws: string; runId: string }) {
  const query = useQuery({
    queryKey: ['ws', ws, 'run-ai-verifications', runId],
    retry: false,
    queryFn: () => call(api.GET('/workspaces/{workspaceId}/runs/{runId}/ai-verifications', { params: { path: { workspaceId: ws, runId } } })),
  })
  const items = query.data?.items ?? []
  if (items.length === 0) return null
  return (
    <Card>
      <CardContent className="space-y-2 pt-4">
        <h2 className="flex items-center gap-2 text-base font-semibold"><FileCheck2 className="size-4" aria-hidden="true" />Document verification</h2>
        <ul className="space-y-2">
          {items.map((v) => (
            <li key={v.id} className="flex flex-wrap items-center justify-between gap-2">
              <span>
                <Link to={`/w/${ws}/runs/${runId}/verifications/${v.id}`}>{v.status === 'open' && query.data?.canVerify ? 'Verify the document' : 'Open the verification'}</Link>
                <span className="text-sm text-muted-foreground"> · {v.fieldCount} fields, {v.needsAttention} need a person · step {v.nodeId}</span>
              </span>
              <Badge tone={v.status === 'open' ? 'warning' : v.status === 'verified' ? 'success' : 'neutral'}>{STATUS[v.status]}</Badge>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}
