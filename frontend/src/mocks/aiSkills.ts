import { http, HttpResponse, type HttpHandler } from 'msw'
import type { components } from '@/api/schema'

type S = components['schemas']

/**
 * UI double of the AiSkills API (docs/build/S134), mock mode and unit tests only: one approved classify skill with a
 * passing evaluation, so the Studio inspector has something to pick, and no AI decisions on mock requests (the mock
 * engine does not run AI tasks; the Test tab simulator mocks their answers).
 */
const B = '/api/v1/workspaces/:ws'

const NOW = '2026-11-05T09:00:00Z'

export const MOCK_AI_SKILL: S['AiSkill'] = {
  key: 'dispute-triage',
  name: 'Dispute triage',
  ownerId: '00000000-0000-7000-8000-00000000a001',
  createdAt: NOW,
  approvedVersion: 1,
  mode: 'classify',
  versions: [{
    skillKey: 'dispute-triage', version: 1, status: 'approved', mode: 'classify', modelProfile: 'economy',
    contentHash: '0'.repeat(64), authorId: '00000000-0000-7000-8000-00000000a001', submittedAt: NOW,
    approverId: '00000000-0000-7000-8000-00000000a002', approvedAt: NOW, autonomyCeiling: 'suggest', driftFallback: false,
    driftReason: null, evaluationGate: { passing: true, runId: '00000000-0000-7000-8000-00000000a003' }, createdAt: NOW,
    updatedAt: NOW,
    document: {
      schemaVersion: '1.0', kind: 'skill', key: 'dispute-triage', name: 'Dispute triage', mode: 'classify',
      instructions: 'Classify the dispute into exactly one label.', modelProfile: 'economy',
      input: { description: { type: 'string', required: true, classification: 'customer-text' }, amount: { type: 'number' } },
      labels: [{ key: 'pricing', description: 'Charged a different amount', risk: 'low' },
        { key: 'duplicate', description: 'Charged twice', risk: 'low' },
        { key: 'fraudSuspected', description: 'Did not authorize the purchase', risk: 'high', adverse: true }],
    },
  }],
}

export function createAiSkillHandlers(): HttpHandler[] {
  return [
    http.get(`${B}/ai-skills`, () => HttpResponse.json({ items: [MOCK_AI_SKILL] } satisfies S['AiSkillList'])),
    http.get(`${B}/runs/:runId/ai-decisions`, () => HttpResponse.json({ valuesVisible: false, items: [] } satisfies S['AiDecisionList'])),
  ]
}
