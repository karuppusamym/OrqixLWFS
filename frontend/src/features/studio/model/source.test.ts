import { beforeEach, describe, expect, it } from 'vitest'
import { signInAs } from '@/test/utils'
import { blankDefinition, db, PURCHASE_WORKFLOW_ID } from '@/mocks/state'
import { canonicalText, checkSource, definitionToYaml, describeChanges, MAX_SOURCE_CHARS, parseSource, roundTrip } from './source'
import { normalizeDefinition, type Definition } from './types'

beforeEach(() => signInAs('dana.designer'))

const seeded = (): Definition => normalizeDefinition(structuredClone(db.workflows.get(PURCHASE_WORKFLOW_ID)!.drafts.at(-1)!.definition))

describe('source mode round trip', () => {
  it('serializes the seeded purchase workflow to YAML and reads back the same document', () => {
    const def = seeded()
    const yaml = definitionToYaml(def)
    expect(yaml).toContain('workflowKey:')
    const parsed = parseSource(yaml)
    expect(parsed.ok).toBe(true)
    if (parsed.ok) expect(canonicalText(parsed.definition)).toBe(canonicalText(def))
    expect(roundTrip(def)).toEqual({ stable: true, detail: '' })
  })

  it('keeps awkward text exactly: yes/no, dates, leading zeros, colons, quotes and multi-line notes', () => {
    const def = seeded()
    def.metadata.description = 'yes'
    def.metadata.stepNotes = { [def.nodes[0]!.id]: 'line one\nline two: "quoted" # not a comment\n  indented' }
    def.nodes[0]!.label = '2026-01-01'
    def.nodes[1]!.label = '0123'
    def.name = 'null'
    expect(roundTrip(def).stable).toBe(true)
    const parsed = parseSource(definitionToYaml(def))
    expect(parsed.ok && parsed.definition.nodes[0]!.label).toBe('2026-01-01')
    expect(parsed.ok && parsed.definition.nodes[1]!.label).toBe('0123')
    expect(parsed.ok && parsed.definition.name).toBe('null')
  })

  it('keeps numbers numbers', () => {
    const def = seeded()
    const parsed = parseSource(definitionToYaml(def).replace(/(maxAttempts: )\d+/, '$17'))
    expect(parsed.ok && parsed.definition.execution.maxAttempts).toBe(7)
    expect(parsed.ok && typeof parsed.definition.execution.maxConcurrentRuns).toBe('number')
  })
})

describe('source mode refuses unsafe or malformed text', () => {
  const messageOf = (text: string) => {
    const r = parseSource(text)
    return r.ok ? '' : r.problems.map((p) => p.message).join(' | ')
  }
  const base = () => definitionToYaml(seeded())

  it('reports YAML syntax errors with a line', () => {
    const r = parseSource(base().replace('nodes:', 'nodes: [unclosed'))
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.problems[0]!.line).toBeGreaterThan(0)
  })
  it('refuses duplicate keys, anchors, aliases and merge keys', () => {
    expect(messageOf('schemaVersion: "0.1"\nname: a\nname: b\n')).toMatch(/duplicated/i)
    expect(messageOf('schemaVersion: "0.1"\nworkflowKey: a\nx: &anchor\n  y: 1\nz: *anchor\n')).toMatch(/anchors, aliases and merge keys/i)
    expect(messageOf('schemaVersion: "0.1"\nworkflowKey: a\nbase:\n  y: 1\nz:\n  <<: *base\n')).toMatch(/anchors, aliases/i)
  })
  it('does not mistake ordinary text for an anchor', () => {
    const def = seeded()
    def.name = 'Tom & Jerry * 2 R&D'
    expect(parseSource(definitionToYaml(def)).ok).toBe(true)
  })
  it('refuses prototype keys, non-finite numbers and the wrong shape', () => {
    expect(messageOf('schemaVersion: "0.1"\nworkflowKey: a\nconstructor:\n  x: 1\n')).toMatch(/not allowed/)
    expect(messageOf('schemaVersion: "0.1"\nworkflowKey: a\nexecution:\n  maxAttempts: .inf\n')).toMatch(/finite/)
    expect(messageOf('- a\n- b\n')).toMatch(/mapping/)
    expect(messageOf('')).toMatch(/empty/)
    expect(messageOf('schemaVersion: 0.1\nworkflowKey: a\n')).toMatch(/schemaVersion/)
    expect(messageOf('schemaVersion: "0.1"\nworkflowKey: a\nnodes: x\n')).toMatch(/nodes must be a list/)
  })
  it('refuses repeated step ids and incomplete connections', () => {
    const def = seeded()
    def.nodes.push({ ...def.nodes[0]! })
    expect(messageOf(definitionToYaml(def))).toMatch(/used twice/)
    const bad = seeded()
    bad.transitions[0] = { id: 'x' } as unknown as Definition['transitions'][number]
    expect(messageOf(definitionToYaml(bad))).toMatch(/transitions\[0\]/)
  })
  it('refuses oversized text', () => {
    expect(messageOf('a'.repeat(MAX_SOURCE_CHARS + 1))).toMatch(/larger than/)
  })
  it('refuses deeply nested documents', () => {
    let deep: unknown = 'x'
    for (let i = 0; i < 60; i++) deep = { a: deep }
    const def = seeded()
    ;(def as unknown as Record<string, unknown>).metadata = { description: '', deep }
    expect(messageOf(definitionToYaml(def))).toMatch(/deeper/)
  })
})

describe('checkSource', () => {
  it('blocks a changed workflowKey and describes added, removed and changed steps', () => {
    const current = seeded()
    const renamed = definitionToYaml(current).replace(`workflowKey: ${current.workflowKey}`, 'workflowKey: another')
    expect(checkSource(renamed, current).ok).toBe(false)

    const edited = seeded()
    edited.nodes[1]!.label = 'Renamed step'
    const removed = edited.nodes.pop()!
    edited.transitions = edited.transitions.filter((t) => t.from !== removed.id && t.to !== removed.id)
    edited.nodes.push({ id: 'extra', type: 'notification', label: 'Extra', config: {} })
    edited.name = 'New name'
    const ok = checkSource(definitionToYaml(edited), current)
    expect(ok.ok).toBe(true)
    if (ok.ok) {
      expect(ok.roundTrip.stable).toBe(true)
      const text = ok.changes.map((c) => `${c.kind}:${c.what}`).join('\n')
      expect(text).toContain('changed:Step "Renamed step"')
      expect(text).toContain(`removed:Step "${removed.label}"`)
      expect(text).toContain('added:Step "Extra"')
      expect(text).toContain('changed:Section name')
    }
  })
  it('reports no change for identical text', () => {
    const current = seeded()
    const ok = checkSource(definitionToYaml(current), current)
    expect(ok.ok && ok.changes).toEqual([])
    expect(describeChanges(current, blankDefinition('k', 'n')).length).toBeGreaterThan(0)
  })
})
