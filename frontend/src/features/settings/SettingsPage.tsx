import { Archive, ClipboardCheck, Plug, ShieldCheck, Sparkles, ToggleRight, Users } from 'lucide-react'
import { useWorkspace } from '@/app/workspace'
import { PageHeader } from '@/components/common/PageHeader'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { DocumentSourcesPanel } from './DocumentSourcesPanel'
import { AccessPanel } from './AccessPanel'
import { AttachmentRetentionPanel } from './AttachmentRetentionPanel'
import { AgentWriteRetentionPanel } from './AgentWriteRetentionPanel'
import { DocumentTemplatesPanel } from './DocumentTemplatesPanel'
import { SharedAssetsPanel } from './SharedAssetsPanel'
import { ConnectionsPanel } from './ConnectionsPanel'
import { MigrationPolicyPanel } from './MigrationPolicyPanel'
import { ExitExportPanel } from './ExitExportPanel'
import { WebhooksPanel } from './WebhooksPanel'
import { KafkaTriggersPanel } from './KafkaTriggersPanel'
import { FeaturesPanel } from './FeaturesPanel'
import { EmptyState } from '@/components/common/EmptyState'
import { AiSettingsPanel, useShowAiSettings } from '@/features/ai/AiSettingsPanel'

/**
 * Tabs run from people and features, through connected systems, to shared content and retention.
 * Settings (docs/build/S7-frontend.md section 4): roles, extra permissions and approval groups (editable by workspace
 * administrators), and connections
 * with an editable binding for people who may manage them. Secret values are never shown or accepted.
 */
export function SettingsPage() {
  const { can } = useWorkspace()
  const showAccess = can('workspace.manage-access')
  const showConnections = can('connection.read')
  const showWebhooks = showConnections || can('run.operate')
  const showTemplates = can('workflow.read')
  const showAssets = showTemplates || showConnections
  const aiConfigured = useShowAiSettings()
  const showAi = aiConfigured && can('workspace.manage-access', 'workflow.edit')

  if (!showAccess && !showConnections && !showTemplates && !showWebhooks) {
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
      <Tabs defaultValue={showAccess ? 'access' : showConnections ? 'connections' : showTemplates ? 'templates' : 'webhooks'}>
        <TabsList aria-label="Settings sections">
          {showAccess ? (
            <TabsTrigger value="access">
              <Users className="size-4" aria-hidden="true" /> Members and groups
            </TabsTrigger>
          ) : null}
          {showAccess ? (
            <TabsTrigger value="features">
              <ToggleRight className="size-4" aria-hidden="true" /> Features
            </TabsTrigger>
          ) : null}
          {showConnections ? (
            <TabsTrigger value="connections">
              <Plug className="size-4" aria-hidden="true" /> Connections
            </TabsTrigger>
          ) : null}
          {showWebhooks ? <TabsTrigger value="webhooks">Webhooks</TabsTrigger> : null}
          {showWebhooks ? <TabsTrigger value="kafka">Kafka</TabsTrigger> : null}
          {showAi ? (
            <TabsTrigger value="ai">
              <Sparkles className="size-4" aria-hidden="true" /> AI
            </TabsTrigger>
          ) : null}
          {showAssets ? <TabsTrigger value="assets">Shared assets</TabsTrigger> : null}
          {showTemplates ? <TabsTrigger value="templates">Document templates</TabsTrigger> : null}
          {showAccess ? <TabsTrigger value="documents">Approved documents</TabsTrigger> : null}
          {showAccess ? (
            <TabsTrigger value="migration">
              <ClipboardCheck className="size-4" aria-hidden="true" /> Migration review
            </TabsTrigger>
          ) : null}
          {showAccess ? <TabsTrigger value="retention">File retention</TabsTrigger> : null}
          {showAccess ? <TabsTrigger value="agent-write-retention">Agent write retention</TabsTrigger> : null}
          {showAccess ? (
            <TabsTrigger value="exit-export">
              <Archive className="size-4" aria-hidden="true" /> Exit export
            </TabsTrigger>
          ) : null}
        </TabsList>
        {showAccess ? <TabsContent value="documents"><DocumentSourcesPanel /></TabsContent> : null}
        {showTemplates ? <TabsContent value="templates"><DocumentTemplatesPanel /></TabsContent> : null}
        {showAssets ? <TabsContent value="assets"><SharedAssetsPanel /></TabsContent> : null}
        {showAccess ? <TabsContent value="retention"><AttachmentRetentionPanel /></TabsContent> : null}
        {showAccess ? <TabsContent value="agent-write-retention"><AgentWriteRetentionPanel /></TabsContent> : null}
        {showAccess ? <TabsContent value="exit-export"><ExitExportPanel /></TabsContent> : null}
        {showAccess ? (
          <TabsContent value="access">
            <AccessPanel />
          </TabsContent>
        ) : null}
        {showAccess ? (
          <TabsContent value="features">
            <FeaturesPanel />
          </TabsContent>
        ) : null}
        {showAccess ? (
          <TabsContent value="migration">
            <MigrationPolicyPanel />
          </TabsContent>
        ) : null}
        {showConnections ? (
          <TabsContent value="connections">
            <ConnectionsPanel />
          </TabsContent>
        ) : null}
        {showWebhooks ? <TabsContent value="webhooks"><WebhooksPanel /></TabsContent> : null}
        {showWebhooks ? <TabsContent value="kafka"><KafkaTriggersPanel /></TabsContent> : null}
        {showAi ? (
          <TabsContent value="ai">
            <AiSettingsPanel />
          </TabsContent>
        ) : null}
      </Tabs>
    </>
  )
}
