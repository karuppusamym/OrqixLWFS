import { setupServer } from 'msw/node'
import { createAiHandlers } from '@/features/ai/mocks'
import { createMigrationHandlers } from '@/features/migration/mocks'
import { createConversationHandlers } from '@/mocks/conversation'
import { createCaseFileHandlers } from '@/mocks/caseFiles'
import { createHandlers, getCurrentUser } from '@/mocks/handlers'
import { createViewHandlers } from '@/mocks/views'
import { createCaseHandlers } from '@/mocks/cases'
import { createPromotionHandlers } from '@/mocks/promotion'
import { createInsightsHandlers } from '@/mocks/insights'
import { createAdapterHandlers } from '@/mocks/adapters'
import { createAgentWriteHandlers } from '@/mocks/agentWrites'
import { createDocumentLibraryHandlers } from '@/mocks/documentLibrary'
import { createSharedAssetHandlers } from '@/mocks/sharedAssets'

/** The same handlers as browser mock mode, served to Vitest. Tests reseed with seedDb() and pick a persona. */
export const server = setupServer(...createAiHandlers(), ...createMigrationHandlers(), ...createConversationHandlers(), ...createInsightsHandlers(), ...createAdapterHandlers(), ...createAgentWriteHandlers(), ...createCaseFileHandlers(), ...createDocumentLibraryHandlers(), ...createSharedAssetHandlers(), ...createViewHandlers(getCurrentUser), ...createCaseHandlers(getCurrentUser), ...createPromotionHandlers(getCurrentUser), ...createHandlers())
