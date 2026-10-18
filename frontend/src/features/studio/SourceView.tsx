import { useMemo, useState } from 'react'
import { useAnnounce } from '@/components/common/Announcer'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { BpmnSourceView } from './BpmnSourceView'
import { useStudio, useStudioContext } from './StudioContext'
import { checkSource, definitionToYaml, MAX_SOURCE_CHARS, roundTrip } from './model/source'

/**
 * Source tab (UI03): the workflow document as YAML (docs/build/S53) or as its compiled BPMN (docs/build/S56). YAML:
 * nothing reaches the draft until the text parses, keeps the workflow key, and survives a serialize/parse round trip;
 * then Apply replaces the draft (one undo step) and the normal autosave and "Check workflow" validation take over.
 * BPMN: see BpmnSourceView.
 */
export function SourceView() {
  const [format, setFormat] = useState<'yaml' | 'bpmn'>('yaml')
  return (
    <div className="mx-auto max-w-4xl space-y-3 p-4" data-testid="source-view">
      <div role="group" aria-label="Source format" className="flex gap-2">
        <Button variant={format === 'yaml' ? 'primary' : 'secondary'} aria-pressed={format === 'yaml'} onClick={() => setFormat('yaml')}>YAML</Button>
        <Button variant={format === 'bpmn' ? 'primary' : 'secondary'} aria-pressed={format === 'bpmn'} onClick={() => setFormat('bpmn')}>BPMN</Button>
      </div>
      {format === 'yaml' ? <YamlSource /> : <BpmnSourceView />}
    </div>
  )
}

function YamlSource() {
  const def = useStudio((s) => s.definition)
  const { store, readOnly } = useStudioContext()
  const announce = useAnnounce()
  const baseline = useMemo(() => definitionToYaml(def), [def])
  const [text, setText] = useState(baseline)
  const [checked, setChecked] = useState<ReturnType<typeof checkSource> | null>(null)
  const unchanged = text === baseline
  const draftTrip = useMemo(() => roundTrip(def), [def])

  const check = () => {
    const result = checkSource(text, store.getState().definition)
    setChecked(result)
    announce(result.ok ? 'The source is valid.' : `The source has ${result.problems.length} ${result.problems.length === 1 ? 'problem' : 'problems'}.`)
    return result
  }
  const apply = () => {
    const result = check()
    if (!result.ok || !result.roundTrip.stable) return
    store.getState().apply(() => result.definition)
    setChecked(null)
    setText(definitionToYaml(result.definition))
    announce('The source was applied to the draft. Run Check workflow to validate it.')
  }

  return (
    <div className="space-y-3" data-testid="yaml-source">
      <p className="text-sm text-muted">
        The workflow as YAML. {readOnly ? 'You can read it but not change it.' : 'Edit it, check it, then apply it to the draft. The same checks as the studio run on Check workflow afterwards.'}
      </p>
      <Alert tone={draftTrip.stable ? 'info' : 'warning'} live="off" data-testid="draft-roundtrip">
        {draftTrip.stable ? 'Round trip: the current draft converts to YAML and back without any difference.' : `Round trip: the current draft does not convert cleanly. ${draftTrip.detail}`}
      </Alert>
      <Textarea
        aria-label="Workflow source (YAML)"
        spellCheck={false}
        readOnly={readOnly}
        value={text}
        onChange={(e) => { setText(e.target.value); setChecked(null) }}
        rows={28}
        className="w-full font-mono text-xs"
        data-testid="source-text"
      />
      <p className="text-xs text-muted">{text.length.toLocaleString('en-US')} of {MAX_SOURCE_CHARS.toLocaleString('en-US')} characters</p>
      {!readOnly ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={check} disabled={unchanged}>Check source</Button>
          <Button onClick={apply} disabled={unchanged}>Apply to draft</Button>
          <Button variant="secondary" onClick={() => { setText(baseline); setChecked(null) }} disabled={unchanged}>Discard edits</Button>
        </div>
      ) : null}
      {checked && !checked.ok ? (
        <Alert tone="danger" title="The source cannot be applied" data-testid="source-problems">
          <ul className="list-disc pl-5">
            {checked.problems.map((p, i) => <li key={i}>{p.line ? `Line ${p.line}: ` : ''}{p.message}</li>)}
          </ul>
        </Alert>
      ) : null}
      {checked?.ok ? (
        <Alert tone={checked.roundTrip.stable ? 'success' : 'danger'} title={checked.roundTrip.stable ? 'The source is valid' : 'The source does not round trip'} data-testid="source-ok">
          <p>{checked.roundTrip.stable ? 'It reads back as the same document.' : checked.roundTrip.detail}</p>
          {checked.changes.length === 0 ? <p>No difference from the draft.</p> : (
            <ul className="mt-1 list-disc pl-5">
              {checked.changes.map((c, i) => <li key={i}>{c.kind === 'added' ? 'Adds' : c.kind === 'removed' ? 'Removes' : 'Changes'}: {c.what}</li>)}
            </ul>
          )}
        </Alert>
      ) : null}
    </div>
  )
}
