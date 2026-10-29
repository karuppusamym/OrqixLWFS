import { describe, expect, it } from 'vitest'
import type { Notification } from '@/api/types'
import { notificationHref } from './NotificationsMenu'

const base = { id: 'n1', message: 'm', createdAt: '2026-10-29T10:00:00Z', readAt: null, runId: null, runReference: null, workflowId: null, elementId: null } as unknown as Notification

describe('where a notification leads (PL02a, S106)', () => {
  it('sends a promotion request to the Promotions page and a decision to the source workflow', () => {
    expect(notificationHref('ws1', { ...base, kind: 'promotionRequest' } as Notification)).toBe('/w/ws1/promotions')
    expect(notificationHref('ws1', { ...base, kind: 'promotionDecision', workflowId: 'wf9' } as Notification)).toBe('/w/ws1/workflows/wf9')
    // A decision about a workflow that no longer exists has nowhere to go.
    expect(notificationHref('ws1', { ...base, kind: 'promotionDecision' } as Notification)).toBeNull()
    expect(notificationHref('ws1', { ...base, kind: 'runAttention', runId: 'r1' } as Notification)).toBe('/w/ws1/runs/r1')
  })
})
