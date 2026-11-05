import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import * as endpoints from '@/api/endpoints'
import type { ProblemError } from '@/api/problem'
import { keys } from '@/api/queries'
import type { components } from '@/api/schema'
import { FormField } from '@/components/common/FormField'
import { ProblemAlert, toProblem } from '@/components/common/ProblemAlert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog } from '@/components/ui/dialog'
import { Input, Select, Textarea } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { fieldErrorMap } from '@/lib/problemMessages'
import { KEY_PATTERN, toKey } from '@/lib/text'
import { adminKeys } from './queries'

type Organization = components['schemas']['Organization']
type WorkspaceAdminView = components['schemas']['WorkspaceAdminView']
type PrincipalRef = components['schemas']['PrincipalRef']

const NEW_ORGANIZATION = '__new__'
const KEY_HELP = 'Lowercase letters, digits and dashes, starting with a letter. It cannot be changed later.'
const KEY_ERROR = 'Use 2 to 64 lowercase letters, digits and dashes, starting with a letter and not ending with a dash.'
const MAX_ADMINS = 20

/** "a, b\nc" -> ["a", "b", "c"]: one sign-in group per line or comma, trimmed, without repeats. */
export function parseGroups(text: string): string[] {
  return [...new Set(text.split(/[\n,]/).map((g) => g.trim()).filter(Boolean))]
}

/**
 * New workspace (platform administrators): name, key, test or not, and the first workspace administrators. When there
 * is no organization yet, or "New organization" is chosen, the organization is created first in the same step.
 * The workspace starts empty; its administrators then add people, roles and approval groups in its Settings.
 */
export function NewWorkspaceDialog({ organizations, meId, onClose, onCreated }: {
  organizations: Organization[]
  meId: string
  onClose: () => void
  onCreated: (workspace: WorkspaceAdminView, addedMe: boolean) => void
}) {
  const client = useQueryClient()
  const [orgChoice, setOrgChoice] = useState(organizations[0]?.id ?? NEW_ORGANIZATION)
  const [orgName, setOrgName] = useState('')
  const [orgKey, setOrgKey] = useState('')
  const [orgKeyTouched, setOrgKeyTouched] = useState(false)
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyTouched, setKeyTouched] = useState(false)
  const [isTest, setIsTest] = useState(false)
  const [selfApproval, setSelfApproval] = useState(false)
  const [addMe, setAddMe] = useState(true)
  const [groupsText, setGroupsText] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ProblemError | null>(null)

  const newOrg = orgChoice === NEW_ORGANIZATION
  const effectiveOrgKey = orgKeyTouched ? orgKey : toKey(orgName)
  const effectiveKey = keyTouched ? key : toKey(name)
  const groups = parseGroups(groupsText)

  const validate = (): Record<string, string> => {
    const next: Record<string, string> = {}
    if (newOrg) {
      if (!orgName.trim()) next.orgName = 'Give the organization a name.'
      if (!KEY_PATTERN.test(effectiveOrgKey)) next.orgKey = KEY_ERROR
    }
    if (!name.trim()) next.name = 'Give the workspace a name.'
    if (!KEY_PATTERN.test(effectiveKey)) next.key = KEY_ERROR
    const count = groups.length + (addMe ? 1 : 0)
    if (count === 0) next.admins = 'Choose at least one administrator: yourself or a sign-in group.'
    else if (count > MAX_ADMINS) next.admins = `Name at most ${MAX_ADMINS} administrators. Use one sign-in group for a larger team.`
    else if (groups.some((g) => g.length > 256)) next.admins = 'A sign-in group name can be at most 256 characters.'
    return next
  }

  const submit = async () => {
    const found = validate()
    setErrors(found)
    if (Object.keys(found).length > 0) return
    setPending(true)
    setError(null)
    let organizationId = orgChoice
    if (newOrg) {
      try {
        const org = await endpoints.createOrganization({ key: effectiveOrgKey, name: orgName.trim() })
        organizationId = org.id
        // From now on the dialog uses the organization it created, so a second try does not create it again.
        setOrgChoice(org.id)
        await client.invalidateQueries({ queryKey: adminKeys.organizations })
      } catch (cause) {
        const problem = toProblem(cause)
        const mapped = fieldErrorMap(problem)
        if (problem.code === 'DUPLICATE_KEY') setErrors({ orgKey: 'An organization with this key already exists. Choose a different key, or choose that organization.' })
        else if (mapped.key || mapped.name) setErrors({ ...(mapped.key ? { orgKey: KEY_ERROR } : {}), ...(mapped.name ? { orgName: 'Use at most 200 characters.' } : {}) })
        else setError(problem)
        setPending(false)
        return
      }
    }
    const initialAdmins: PrincipalRef[] = [
      ...(addMe ? [{ type: 'subject' as const, value: meId }] : []),
      ...groups.map((value) => ({ type: 'idpGroup' as const, value })),
    ]
    try {
      const created = await endpoints.createWorkspace({
        organizationId,
        key: effectiveKey,
        name: name.trim(),
        isTest,
        allowSelfApprovalWorkflows: isTest && selfApproval,
        initialAdmins,
      })
      // The new workspace appears in the workspace switcher once /me is read again.
      await Promise.all([client.invalidateQueries({ queryKey: keys.me }), client.invalidateQueries({ queryKey: adminKeys.workspaces })])
      onCreated(created, addMe)
    } catch (cause) {
      const problem = toProblem(cause)
      const mapped = fieldErrorMap(problem)
      if (problem.code === 'DUPLICATE_KEY') setErrors({ key: 'A workspace with this key already exists. Choose a different key.' })
      else if (mapped.key) setErrors({ key: KEY_ERROR })
      else if (mapped.name) setErrors({ name: 'Use at most 200 characters.' })
      else if (mapped.initialAdmins) setErrors({ admins: mapped.initialAdmins })
      else setError(problem)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      size="lg"
      title="New workspace"
      description="A workspace holds its own workflows, requests, people and connections. It starts empty."
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>Cancel</Button>
          <Button variant="primary" onClick={() => void submit()} loading={pending}>Create workspace</Button>
        </>
      }
    >
      <div className="space-y-5">
        <fieldset className="space-y-3">
          <legend className="text-sm font-semibold">Organization</legend>
          {organizations.length > 0 ? (
            <div className="space-y-1.5">
              <Label htmlFor="ws-org">Belongs to</Label>
              <Select id="ws-org" value={orgChoice} onChange={(e) => setOrgChoice(e.target.value)} disabled={pending}>
                {organizations.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                <option value={NEW_ORGANIZATION}>New organization…</option>
              </Select>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">Every workspace belongs to an organization. There is none yet, so this creates the first one.</p>
          )}
          {newOrg ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label="Organization name" required id="org-name" error={errors.orgName}>
                {(ids) => <Input id={ids.input} value={orgName} onChange={(e) => setOrgName(e.target.value)} maxLength={200} aria-invalid={errors.orgName ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />}
              </FormField>
              <FormField label="Organization key" required id="org-key" error={errors.orgKey} description="Filled in from the name.">
                {(ids) => <Input id={ids.input} value={effectiveOrgKey} onChange={(e) => { setOrgKey(e.target.value); setOrgKeyTouched(true) }} maxLength={64} aria-invalid={errors.orgKey ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
              </FormField>
            </div>
          ) : null}
        </fieldset>

        <FormField label="Workspace name" required id="ws-name" error={errors.name}>
          {(ids) => <Input id={ids.input} value={name} onChange={(e) => setName(e.target.value)} maxLength={200} aria-invalid={errors.name ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" />}
        </FormField>
        <FormField label="Workspace key" required id="ws-key" error={errors.key} description={`Filled in from the name. ${KEY_HELP}`}>
          {(ids) => <Input id={ids.input} value={effectiveKey} onChange={(e) => { setKey(e.target.value); setKeyTouched(true) }} maxLength={64} aria-invalid={errors.key ? true : undefined} aria-describedby={ids.describedBy} autoComplete="off" spellCheck={false} />}
        </FormField>

        <fieldset className="space-y-3">
          <legend className="text-sm font-semibold">Kind of workspace</legend>
          <div className="flex items-start gap-3">
            <Checkbox id="ws-test" checked={isTest} onCheckedChange={(checked) => { setIsTest(checked === true); if (checked !== true) setSelfApproval(false) }} aria-describedby="ws-test-help" />
            <div>
              <Label htmlFor="ws-test">This is a test workspace</Label>
              <p id="ws-test-help" className="text-sm text-muted-foreground">Shows a test banner. Use it to try workflows with test connections before promoting them to a live workspace.</p>
            </div>
          </div>
          <div className="flex items-start gap-3">
            <Checkbox id="ws-self" checked={isTest && selfApproval} disabled={!isTest} onCheckedChange={(checked) => setSelfApproval(checked === true)} aria-describedby="ws-self-help" />
            <div>
              <Label htmlFor="ws-self">Allow workflows where people approve their own requests</Label>
              <p id="ws-self-help" className="text-sm text-muted-foreground">Only test workspaces can allow this. Handy when one person tries out a whole workflow.</p>
            </div>
          </div>
        </fieldset>

        <fieldset className="space-y-3" aria-describedby={errors.admins ? 'ws-admins-error' : undefined}>
          <legend className="text-sm font-semibold">Workspace administrators</legend>
          <p className="text-sm text-muted-foreground">They manage who can do what in the workspace. Administrators do not see requests or approve unless they also give themselves those roles.</p>
          <div className="flex items-start gap-3">
            <Checkbox id="ws-add-me" checked={addMe} onCheckedChange={(checked) => setAddMe(checked === true)} aria-describedby="ws-add-me-help" />
            <div>
              <Label htmlFor="ws-add-me">Make me a workspace administrator</Label>
              <p id="ws-add-me-help" className="text-sm text-muted-foreground">Lets you open the workspace now and add people to it.</p>
            </div>
          </div>
          <FormField label="Sign-in groups" optionalText id="ws-groups" description="One group per line, by the name your identity provider sends at sign-in, for example purchasing-admins. Everyone in the group becomes an administrator.">
            {(ids) => <Textarea id={ids.input} value={groupsText} onChange={(e) => setGroupsText(e.target.value)} rows={2} aria-describedby={ids.describedBy} spellCheck={false} />}
          </FormField>
          {errors.admins ? <p id="ws-admins-error" className="text-sm font-medium text-danger">{errors.admins}</p> : null}
        </fieldset>
        {error ? <ProblemAlert error={error} context={{ action: 'create a workspace' }} /> : null}
      </div>
    </Dialog>
  )
}
