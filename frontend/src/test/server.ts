import { setupServer } from 'msw/node'
import { createAiHandlers } from '@/features/ai/mocks'
import { createMigrationHandlers } from '@/features/migration/mocks'
import { createConversationHandlers } from '@/mocks/conversation'
import { createCaseFileHandlers } from '@/mocks/caseFiles'
import { createHandlers } from '@/mocks/handlers'
import { createInsightsHandlers } from '@/mocks/insights'
import { createAdapterHandlers } from '@/mocks/adapters'

/** The same handlers as browser mock mode, served to Vitest. Tests reseed with seedDb() and pick a persona. */
export const server = setupServer(...createAiHandlers(), ...createMigrationHandlers(), ...createConversationHandlers(), ...createInsightsHandlers(), ...createAdapterHandlers(), ...createCaseFileHandlers(), ...createHandlers())
