import type { components } from '@/api/schema'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

type Coverage = NonNullable<components['schemas']['TestCoverage']>
type Minimum = components['schemas']['TestCoverageMinimum']
type Kind = 'steps' | 'routes' | 'rules' | 'formRules'

const KINDS: Array<{ kind: Kind; label: string; noun: string }> = [
  { kind: 'steps', label: 'Steps', noun: 'steps' },
  { kind: 'routes', label: 'Routes', noun: 'routes' },
  { kind: 'rules', label: 'Decision rules', noun: 'decision rules' },
  { kind: 'formRules', label: 'Form rules', noun: 'form rules' },
]

/** One line for an item no case covered, in words. */
function describe(kind: Kind, item: Record<string, unknown>): string {
  const text = (v: unknown) => (typeof v === 'string' ? v : '')
  if (kind === 'steps') return text(item.label) || text(item.id)
  if (kind === 'routes') return text(item.description) || `${text(item.from)} to ${text(item.to)}`
  if (kind === 'rules') return `${text(item.decision)}: ${text(item.row)}${item.condition ? ` (${text(item.condition)})` : ''}`
  const what = item.rule === 'requiredWhen' ? 'required' : 'shown'
  const subject = item.section ? `the section ${text(item.label)}` : text(item.label)
  return item.missing === 'fails'
    ? `No case where ${subject} is not ${what}`
    : `No case where ${subject} is ${what}`
}

/**
 * CG-Q03 (S148): what the saved cases cover together after "Run all saved cases": steps reached, routes taken,
 * decision rules matched, and form rules seen both holding and not holding.
 */
export function SuiteCoverageView({ coverage, onGoToStep }: { coverage: Coverage; onGoToStep: (stepId: string) => void }) {
  return (
    <section aria-labelledby="suite-coverage-title" className="mt-2" data-testid="suite-coverage">
      <h4 id="suite-coverage-title" className="text-sm font-semibold">
        What these cases cover together
      </h4>
      <p className="text-sm">{coverage.summary}</p>
      <ul className="mt-1 grid grid-cols-2 gap-1 text-sm sm:grid-cols-4">
        {KINDS.map(({ kind, label }) => (
          <li key={kind} data-testid={`coverage-${kind}`}>
            <span className="font-semibold">{label}</span>: {coverage[kind].percent}% ({coverage[kind].covered} of {coverage[kind].total})
          </li>
        ))}
      </ul>
      {KINDS.filter(({ kind }) => coverage[kind].notCovered.length > 0).map(({ kind, noun }) => (
        <details key={kind} className="mt-1">
          <summary className="cursor-pointer text-sm">
            {noun.charAt(0).toUpperCase() + noun.slice(1)} no case covers ({coverage[kind].total - coverage[kind].covered})
          </summary>
          <ul className="mt-1 list-disc pl-5 text-sm">
            {coverage[kind].notCovered.map((item, i) => (
              <li key={i}>
                {describe(kind, item as Record<string, unknown>)}
                {kind === 'steps' && typeof (item as Record<string, unknown>).id === 'string' ? (
                  <Button variant="link" className="ml-2" onClick={() => onGoToStep(String((item as Record<string, unknown>).id))}>
                    Go to step
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </details>
      ))}
    </section>
  )
}

/** The editable coverage minimum: the typed text per kind, what it parses to, and why it does not parse. */
export interface MinimumDraft {
  text: Record<Kind, string>
  parsed?: Minimum
  error?: string
}

export function minimumFrom(minimum: Minimum | undefined): MinimumDraft {
  const text = Object.fromEntries(KINDS.map(({ kind }) => [kind, minimum?.[kind] === undefined ? '' : String(minimum[kind])])) as Record<Kind, string>
  return parse(text)
}

function parse(text: Record<Kind, string>): MinimumDraft {
  const parsed: Minimum = {}
  for (const { kind, label } of KINDS) {
    const value = text[kind].trim()
    if (value === '') continue
    if (!/^\d{1,3}$/.test(value) || Number(value) > 100) return { text, error: `${label}: give a whole percentage from 0 to 100, or leave it empty.` }
    parsed[kind] = Number(value)
  }
  return { text, parsed: Object.keys(parsed).length > 0 ? parsed : undefined }
}

/**
 * The least coverage the shared tests must reach for publication (CG-Q03). Saved with "Share and test for publication";
 * empty means no minimum for that kind. Readers see the minimum but cannot change it.
 */
export function CoverageMinimumEditor({ value, onChange, canEdit }: { value: MinimumDraft; onChange: (next: MinimumDraft) => void; canEdit: boolean }) {
  if (!canEdit) {
    const set = KINDS.filter(({ kind }) => value.text[kind] !== '')
    return set.length === 0 ? null : (
      <p className="text-sm" data-testid="coverage-minimum">
        Publication needs at least {set.map(({ kind, noun }) => `${value.text[kind]}% of ${noun}`).join(', ')} covered.
      </p>
    )
  }
  return (
    <fieldset className="mt-2" data-testid="coverage-minimum">
      <legend className="text-sm font-semibold">Least coverage for publication (percent, optional)</legend>
      <div className="mt-1 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {KINDS.map(({ kind, label }) => (
          <div key={kind}>
            <Label htmlFor={`coverage-minimum-${kind}`}>{label}</Label>
            <Input
              id={`coverage-minimum-${kind}`}
              inputMode="numeric"
              value={value.text[kind]}
              onChange={(e) => onChange(parse({ ...value.text, [kind]: e.target.value }))}
              aria-invalid={value.error?.startsWith(label) ? true : undefined}
            />
          </div>
        ))}
      </div>
      {value.error ? (
        <p className="mt-1 text-sm text-destructive" role="alert">
          {value.error}
        </p>
      ) : (
        <p className="mt-1 text-xs text-muted-foreground">Saved with Share and test for publication. A shared run below the minimum blocks publishing.</p>
      )}
    </fieldset>
  )
}
