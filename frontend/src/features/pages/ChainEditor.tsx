import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input, Select } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { moveItem } from '@/features/views/model'
import { allComponents, chainProblems, PLAN_ITEM_STEPS, STEP_ACTIONS, STEP_LABELS, stepTargets, uniqueComponentId, type ActionChain, type ChainAction, type ChainStep, type PageDefinition, type PageLookups } from './model'

interface ChainEditorProps {
  page: PageDefinition
  lookups: PageLookups
  disabled: boolean
  onChange: (chains: ActionChain[]) => void
}

/**
 * Composed actions (S111): an ordered list of allowlisted steps. Each step targets a component whose pinned view suits
 * it; navigation can only come last. Chains always stop at the first failure, and the server authorizes every step.
 */
export function ChainEditor({ page, lookups, disabled, onChange }: ChainEditorProps) {
  const chains = page.chains
  const byId = new Map(allComponents(page).map(component => [component.id, component]))
  const change = (index: number, transform: (chain: ActionChain) => ActionChain) => onChange(chains.map((chain, at) => at === index ? transform(chain) : chain))
  const changeStep = (index: number, stepIndex: number, transform: (step: ChainStep) => ChainStep) => change(index, chain => ({ ...chain, steps: chain.steps.map((step, at) => at === stepIndex ? transform(step) : step) }))
  const add = () => {
    const id = uniqueComponentId(page, 'chain')
    const target = stepTargets(page, 'submit', lookups)[0]
    onChange([...chains, { id, label: 'New action', stopOnFailure: true, steps: [target ? { action: 'submit', component: target.id } : { action: 'navigate', target: '/w' }] }])
  }
  return <section className="space-y-3 rounded-card border border-border bg-surface p-4" aria-label="Action chains">
    <div className="flex items-center justify-between gap-2"><h2 className="text-lg font-semibold">Action chains</h2><Button size="sm" variant="secondary" disabled={disabled || chains.length >= 10} onClick={add}><Plus aria-hidden="true" /> Add chain</Button></div>
    <p className="text-sm text-muted-foreground">Each step uses the fixed action list and is checked again by the server. A chain stops at the first failed step; earlier steps are not undone. Case steps take the reason entered on their case component and use the case version the page loaded.</p>
    {chains.length === 0 ? <p className="text-sm text-muted-foreground">No chains yet. Add one, then place it on an action bar or grant it to an extension.</p> : null}
    {chains.map((chain, index) => {
      const problems = chainProblems(chain, byId, lookups)
      return <fieldset key={chain.id} className="space-y-3 rounded border border-border p-3"><legend className="px-1 font-semibold">{chain.label || chain.id}</legend>
        <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto]">
          <div><Label htmlFor={`chain-${chain.id}-label`}>Button label</Label><Input id={`chain-${chain.id}-label`} value={chain.label} maxLength={80} disabled={disabled} onChange={event => change(index, old => ({ ...old, label: event.target.value }))} /></div>
          <div className="flex items-end"><Button variant="ghost" size="sm" disabled={disabled} aria-label={`Remove chain ${chain.label || chain.id}`} onClick={() => onChange(chains.filter((_, at) => at !== index))}><Trash2 aria-hidden="true" /> Remove</Button></div>
        </div>
        <div className="flex items-center gap-2"><Checkbox id={`chain-${chain.id}-stop`} checked={chain.stopOnFailure} disabled={disabled} onCheckedChange={checked => change(index, old => ({ ...old, stopOnFailure: checked === true }))} /><Label htmlFor={`chain-${chain.id}-stop`}>Stop at the first failed step (required)</Label></div>
        <ol className="space-y-2">{chain.steps.map((step, stepIndex) => {
          const targets = stepTargets(page, step.action, lookups)
          const name = `${chain.label || chain.id} step ${stepIndex + 1}`
          return <li key={stepIndex} className="grid gap-2 rounded bg-surface-muted p-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
            <div><Label htmlFor={`chain-${chain.id}-${stepIndex}-action`}>Step {stepIndex + 1} action</Label><Select id={`chain-${chain.id}-${stepIndex}-action`} value={step.action} disabled={disabled} onChange={event => {
              const action = event.target.value as ChainAction
              changeStep(index, stepIndex, () => action === 'navigate' ? { action, target: step.target ?? '/w' } : { action, component: stepTargets(page, action, lookups)[0]?.id ?? step.component, ...(PLAN_ITEM_STEPS.includes(action) ? { planItem: step.planItem ?? '' } : {}) })
            }}>{STEP_ACTIONS.map(action => <option key={action} value={action}>{STEP_LABELS[action]}</option>)}</Select></div>
            {step.action === 'navigate'
              ? <div><Label htmlFor={`chain-${chain.id}-${stepIndex}-target`}>Same-app path</Label><Input id={`chain-${chain.id}-${stepIndex}-target`} value={step.target ?? ''} placeholder="/w/..." disabled={disabled} onChange={event => changeStep(index, stepIndex, old => ({ ...old, target: event.target.value }))} /></div>
              : <div><Label htmlFor={`chain-${chain.id}-${stepIndex}-component`}>Target component</Label><Select id={`chain-${chain.id}-${stepIndex}-component`} value={step.component ?? ''} disabled={disabled} onChange={event => changeStep(index, stepIndex, old => ({ ...old, component: event.target.value }))}><option value="">Choose a component</option>{targets.map(component => <option key={component.id} value={component.id}>{component.label ?? component.id}</option>)}{step.component && !targets.some(component => component.id === step.component) ? <option value={step.component}>{step.component} (not compatible)</option> : null}</Select>
                {PLAN_ITEM_STEPS.includes(step.action) ? <div className="mt-2"><Label htmlFor={`chain-${chain.id}-${stepIndex}-item`}>Case task or optional item (CMMN element id)</Label><Input id={`chain-${chain.id}-${stepIndex}-item`} value={step.planItem ?? ''} maxLength={64} placeholder="reviewTask" disabled={disabled} onChange={event => changeStep(index, stepIndex, old => ({ ...old, planItem: event.target.value }))} /></div> : null}</div>}
            <div className="flex items-end gap-1">
              <Button size="icon" variant="ghost" aria-label={`Move ${name} up`} disabled={disabled || stepIndex === 0} onClick={() => change(index, old => ({ ...old, steps: moveItem(old.steps, stepIndex, -1) }))}><ArrowUp aria-hidden="true" /></Button>
              <Button size="icon" variant="ghost" aria-label={`Move ${name} down`} disabled={disabled || stepIndex === chain.steps.length - 1} onClick={() => change(index, old => ({ ...old, steps: moveItem(old.steps, stepIndex, 1) }))}><ArrowDown aria-hidden="true" /></Button>
              <Button size="icon" variant="ghost" aria-label={`Remove ${name}`} disabled={disabled || chain.steps.length === 1} onClick={() => change(index, old => ({ ...old, steps: old.steps.filter((_, at) => at !== stepIndex) }))}><Trash2 aria-hidden="true" /></Button>
            </div>
          </li>
        })}</ol>
        <Button size="sm" variant="secondary" disabled={disabled || chain.steps.length >= 5} onClick={() => change(index, old => ({ ...old, steps: [...old.steps, { action: 'navigate', target: '/w' }] }))}><Plus aria-hidden="true" /> Add step</Button>
        {problems.length ? <ul className="list-disc space-y-1 pl-5 text-sm text-danger" aria-label={`Problems in ${chain.label || chain.id}`}>{problems.map(problem => <li key={problem}>{problem}</li>)}</ul> : null}
      </fieldset>
    })}
  </section>
}
