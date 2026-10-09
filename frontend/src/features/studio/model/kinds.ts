import type { NodeKind, RouteKind } from './types'

/** Business vocabulary for the palette and node cards (docs/build/S7-frontend.md section 5). */
export interface KindInfo {
  kind: NodeKind
  /** Palette label. */
  paletteLabel: string
  /** Small caption on the node card. */
  caption: string
  /** One-line explanation shown in the palette. */
  description: string
  defaultLabel: string
  /** Outgoing route kinds a node of this kind needs, in display order. */
  routes: RouteKind[]
}

export const KINDS: Record<NodeKind, KindInfo> = {
  start: {
    kind: 'start',
    paletteLabel: 'Request form',
    caption: 'Request form',
    description: 'The questions the requester answers. Every workflow starts here.',
    defaultLabel: 'Request submitted',
    routes: ['next'],
  },
  approval: {
    kind: 'approval',
    paletteLabel: 'Ask for approval',
    caption: 'Approval',
    description: 'A person or group approves or rejects the request.',
    defaultLabel: 'Review request',
    routes: ['approved', 'rejected'],
  },
  decision: {
    kind: 'decision',
    paletteLabel: 'Make a decision',
    caption: 'Decision',
    description: 'A rule table turns a value, such as the amount, into a result.',
    defaultLabel: 'Check the request',
    routes: ['next'],
  },
  branch: {
    kind: 'branch',
    paletteLabel: 'Choose a path',
    caption: 'Choose a path',
    description: 'Send the request down different paths depending on a value.',
    defaultLabel: 'Which path?',
    routes: ['condition', 'default'],
  },
  http: {
    kind: 'http',
    paletteLabel: 'Call a system',
    caption: 'Call a system',
    description: 'Use an approved connection to do work in another system.',
    defaultLabel: 'Call the other system',
    routes: ['next'],
  },
  notification: {
    kind: 'notification',
    paletteLabel: 'Notify someone',
    caption: 'Notification',
    description: 'Send an in-app message to the requester.',
    defaultLabel: 'Tell the requester',
    routes: ['next'],
  },
  end: {
    kind: 'end',
    paletteLabel: 'Finish',
    caption: 'Finish',
    description: 'End the workflow with a named business outcome.',
    defaultLabel: 'Finished',
    routes: [],
  },
}

/** Palette order. The start step always exists and cannot be added or deleted. */
export const PALETTE_KINDS: NodeKind[] = ['approval', 'decision', 'branch', 'http', 'notification', 'end']

export const ROUTE_LABEL: Record<RouteKind, string> = {
  next: 'Next',
  approved: 'Approved',
  rejected: 'Rejected',
  condition: 'When',
  default: 'Otherwise',
}

/** Default config for a new node of each kind. */
export function defaultConfig(kind: NodeKind): Record<string, unknown> {
  switch (kind) {
    case 'start':
    case 'branch':
      return {}
    case 'approval':
      return { candidateGroupRef: '', rejectReasonRequired: true }
    case 'decision':
      return { decisionRef: '' }
    case 'http':
      return { connectionRef: '', operation: '', inputMapping: {}, resultMapping: {}, timeoutSeconds: 10 }
    case 'notification':
      return { channel: 'inApp', recipient: 'initiator', message: '' }
    case 'end':
      return { businessOutcome: 'Completed', outcomeKind: 'success' }
  }
}
