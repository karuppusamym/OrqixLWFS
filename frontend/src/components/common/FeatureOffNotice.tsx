import type { WorkspaceFeature } from '@/api/types'
import { useWorkspace } from '@/app/workspace'
import { Alert } from '@/components/ui/alert'
import { featureInfo } from '@/lib/features'

/** Which module a workspace page belongs to (S127), for pages reached by a link while the module is off. */
export function featureOfPath(pathname: string): WorkspaceFeature | null {
  const rest = pathname.replace(/^\/w\/[^/]+/, '')
  if (/^\/cases(\/|$)/.test(rest)) return 'cases'
  if (/^\/(apps|extensions)(\/|$)/.test(rest)) return 'apps'
  if (/^\/migrations(\/|$)/.test(rest)) return 'migration'
  if (/^\/agent-writes(\/|$)/.test(rest)) return 'agents'
  return null
}

/** Says that a module is switched off, what still works, and who can switch it on. Renders nothing while it is on. */
export function FeatureOffNotice({ feature, className }: { feature: WorkspaceFeature | null; className?: string }) {
  const { isOn, can } = useWorkspace()
  if (!feature || isOn(feature)) return null
  const info = featureInfo(feature)
  return (
    <Alert tone="info" className={className} title={`${info.name} is switched off in this workspace`} data-testid="feature-off-notice">
      {info.whenOff} {info.running}{' '}
      {can('workspace.manage-access') ? 'You can switch it on in Settings, Features.' : 'A workspace administrator can switch it on again.'}
    </Alert>
  )
}
