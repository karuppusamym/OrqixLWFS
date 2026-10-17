import type { components } from '@/api/schema'

export type MigrationReview = components['schemas']['MigrationReview']

export interface PersonContext {
  /** The signed-in person. */
  meId: string
  /** Who saved the latest draft revision. */
  draftAuthorId: string | null | undefined
}

/**
 * Why this person cannot approve the review, or null when they can. The server decides (canApprove and the approval
 * rules); these sentences only explain which rule applies, in the order the server checks them.
 */
export function approveBlock(review: MigrationReview, who: PersonContext, mayPublish: boolean): string | null {
  if (!mayPublish) return 'You need permission to publish workflows to approve a migration review.'
  if (review.mapper === null) return 'This conversion has no recorded mapper, so it cannot be approved. Import the source again, or ask a workspace administrator to turn off the independent review setting.'
  if (review.mapper.id === who.meId) return 'You mapped this import, so you cannot approve it. Ask a colleague who can publish workflows to review it.'
  if (who.draftAuthorId === who.meId) return 'You saved the latest draft revision, so you cannot approve it. Ask a colleague who can publish workflows to review it.'
  if (!review.canApprove) return 'The server does not allow you to approve this review. Reload the page to see the current state.'
  return null
}

/**
 * Why publishing this draft would be refused by the independent review policy, or null when the policy does not stand
 * in the way. Publishing also needs the ordinary permission and a valid draft; those are shown elsewhere.
 */
export function publishBlock(review: MigrationReview | undefined, who: PersonContext, requestedRevision: number): string | null {
  if (!review || !review.migration || !review.required) return null
  if (requestedRevision !== review.draftRevision) return null // The page already says a newer draft exists.
  if (!review.approved) {
    return review.reviewedBy
      ? `The approval by ${review.reviewedBy.displayName} no longer applies: the draft, its source mappings or the reviewer’s publishing access changed. A new independent review of this exact draft is needed.`
      : 'Workspace policy requires an independent review of this exact draft before a migrated workflow can be published. Someone other than the mapper and the draft author must approve it.'
  }
  if (review.mapper?.id === who.meId) return 'You mapped this import, so a different person must publish it.'
  if (review.reviewedBy?.id === who.meId) return 'You reviewed this migration, so a different person must publish it.'
  if (who.draftAuthorId === who.meId) return 'You saved the latest draft revision, so a different person must publish it.'
  return null
}
