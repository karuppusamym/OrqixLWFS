import { setupServer } from 'msw/node'
import { createAiHandlers } from '@/features/ai/mocks'
import { createMigrationHandlers } from '@/features/migration/mocks'
import { createConversationHandlers } from '@/mocks/conversation'
import { createHandlers } from '@/mocks/handlers'

/** The same handlers as browser mock mode, served to Vitest. Tests reseed with seedDb() and pick a persona. */
export const server = setupServer(...createAiHandlers(), ...createMigrationHandlers(), ...createConversationHandlers(), ...createHandlers())
