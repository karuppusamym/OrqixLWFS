import { Plug, ShieldCheck, Sparkles, Users } from 'lucide-react'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { AccessPanel } from './AccessPanel'
import { ConnectionsPanel } from './ConnectionsPanel'
import { EmptyState } from '@/components/common/EmptyState'
import { AiSettingsPanel, useShowAiSettings } from '@/features/ai/AiSettingsPanel'

/**
 * Settings (docs/build/S7-frontend.md section 4): members, roles and groups read-only in R0, and connections
 * with an editable binding for people who may manage them. Secret values are never shown or accepted.
 */
export function SettingsPage() {
  const { can } = useWorkspace()
  const showAccess = can('workspace.manage-access')
  const showConnections = can('connection.read')
  const aiConfigured = useShowAiSettings()
  const showAi = aiConfigured && can('workspace.manage-access', 'workflow.edit')

  if (!showAccess && !showConnections) {
    return (
      <>
        <PageHeader title="Settings" />
        <EmptyState icon={ShieldCheck} title="Nothing to show here">
          You don&apos;t have access to workspace settings.
        </EmptyState>
      </>
    )
  }

  return (
    <>
      <PageHeader title="Settings" description="Who can do what in this workspace, and which systems it can reach." />
      <Tabs defaultValue={showAccess ? 'access' : 'connections'}>
        <TabsList aria-label="Settings sections">
          {showAccess ? (
            <TabsTrigger value="access">
              <Users className="size-4" aria-hidden="true" /> Members and groups
            </TabsTrigger>
          ) : null}
          {showConnections ? (
            <TabsTrigger value="connections">
              <Plug className="size-4" aria-hidden="true" /> Connections
            </TabsTrigger>
          ) : null}
          {showAi ? (
            <TabsTrigger value="ai">
              <Sparkles className="size-4" aria-hidden="true" /> AI
            </TabsTrigger>
          ) : null}
        </TabsList>
        {showAccess ? (
          <TabsContent value="access">
            <AccessPanel />
          </TabsContent>
        ) : null}
        {showConnections ? (
          <TabsContent value="connections">
            <ConnectionsPanel />
          </TabsContent>
        ) : null}
        {showAi ? (
          <TabsContent value="ai">
            <AiSettingsPanel />
          </TabsContent>
        ) : null}
      </Tabs>
    </>
  )
}
