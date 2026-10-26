/**
 * Safe text-answer patterns (docs/build/S90): a line-by-line port of the server engine
 * `backend/compiler/src/main/java/com/orqix/compiler/FormPattern.java`. Answers are matched by simulating a Thompson
 * NFA, never with the browser's backtracking RegExp, so matching is linear in the answer length whatever the pattern.
 * The accepted subset means the same as ECMAScript RegExp with the `u` flag; `pattern.test.ts` checks both engines
 * against the shared cases in examples/fixtures/form-pattern-cases.json. The server is authoritative.
 */

export const MAX_PATTERN_LENGTH = 200
export const MAX_INPUT_LENGTH = 4000
export const MAX_REPEAT = 100
export const MAX_STATES = 2000
const MAX_GROUP_DEPTH = 20
const MAX_EXAMPLE_SETS = 2000
const MAX_BUILD_STEPS = 50_000

export class PatternError extends Error {}

interface Item { ranges: number[]; negated: boolean }
interface Pred { items: Item[]; negated: boolean }

function itemMatches(item: Item, cp: number): boolean {
  let inside = false
  for (let i = 0; i < item.ranges.length; i += 2) {
    if (cp >= item.ranges[i]! && cp <= item.ranges[i + 1]!) { inside = true; break }
  }
  return inside !== item.negated
}

function predMatches(pred: Pred, cp: number): boolean {
  let any = false
  for (const item of pred.items) if (itemMatches(item, cp)) { any = true; break }
  return any !== pred.negated
}

function representative(pred: Pred): number {
  if (!pred.negated) for (const item of pred.items) if (!item.negated && item.ranges.length > 0) return item.ranges[0]!
  const scans: Array<[number, number]> = [[0x61, 0x7a], [0x41, 0x5a], [0x30, 0x39], [0x20, 0x2ff]]
  for (const [lo, hi] of scans) for (let c = lo; c <= hi; c++) if (predMatches(pred, c)) return c
  return -1
}

const DIGIT = [0x30, 0x39]
const WORD = [0x30, 0x39, 0x41, 0x5a, 0x5f, 0x5f, 0x61, 0x7a]
const SPACE = [0x09, 0x0d, 0x20, 0x20, 0xa0, 0xa0, 0x1680, 0x1680, 0x2000, 0x200a, 0x2028, 0x2029, 0x202f, 0x202f, 0x205f, 0x205f, 0x3000, 0x3000, 0xfeff, 0xfeff]
const LINE_TERMINATORS = [0x0a, 0x0a, 0x0d, 0x0d, 0x2028, 0x2029]
const SYNTAX = '^$\\.*+?()[]{}|/'

const single = (cp: number): Pred => ({ items: [{ ranges: [cp, cp], negated: false }], negated: false })
const show = (c: number) => `"${String.fromCodePoint(c)}"`

type Node =
  | { t: 'chars'; pred: Pred }
  | { t: 'seq'; items: Node[] }
  | { t: 'alt'; branches: Node[] }
  | { t: 'repeat'; node: Node; min: number; max: number }

class Parser {
  pos = 0
  depth = 0
  topLevelAlternation = false
  readonly end: number
  private readonly cps: number[]
  constructor(cps: number[]) { this.cps = cps; this.end = cps.length - 1 }

  parsePattern(): Node {
    const cps = this.cps
    if (cps[0] !== 0x5e || cps.length < 2 || cps[cps.length - 1] !== 0x24 || this.escapedAt(cps.length - 1)) {
      throw new PatternError('Start the pattern with ^ and end it with $, so that it applies to the whole answer.')
    }
    this.pos = 1
    const tree = this.parseAlt()
    if (this.pos !== this.end) throw new PatternError(cps[this.pos] === 0x29 ? 'There is a ) without a matching (.' : `Unexpected ${show(cps[this.pos]!)}.`)
    if (this.topLevelAlternation) throw new PatternError('Put alternatives in a group, for example ^(red|blue)$: at the top level, ^ and $ would only apply to the first and last alternative.')
    return tree
  }

  private escapedAt(index: number): boolean {
    let slashes = 0
    for (let i = index - 1; i >= 0 && this.cps[i] === 0x5c; i--) slashes++
    return slashes % 2 === 1
  }

  private parseAlt(): Node {
    const branches = [this.parseSeq()]
    while (this.pos < this.end && this.cps[this.pos] === 0x7c) {
      if (this.depth === 0) this.topLevelAlternation = true
      this.pos++
      branches.push(this.parseSeq())
    }
    return branches.length === 1 ? branches[0]! : { t: 'alt', branches }
  }

  private parseSeq(): Node {
    const items: Node[] = []
    while (this.pos < this.end && this.cps[this.pos] !== 0x7c && this.cps[this.pos] !== 0x29) {
      let atom = this.parseAtom()
      if (this.pos < this.end && isQuantifierStart(this.cps[this.pos]!)) {
        atom = this.parseQuantifier(atom)
        if (this.pos < this.end && this.cps[this.pos] === 0x3f) throw new PatternError('Lazy quantifiers (such as *? or +?) are not supported.')
        if (this.pos < this.end && isQuantifierStart(this.cps[this.pos]!)) throw new PatternError(`A repeat cannot follow another repeat (${show(this.cps[this.pos]!)}).`)
      }
      items.push(atom)
    }
    return items.length === 1 ? items[0]! : { t: 'seq', items }
  }

  private parseQuantifier(atom: Node): Node {
    const c = this.cps[this.pos++]
    if (c === 0x2a) return { t: 'repeat', node: atom, min: 0, max: -1 }
    if (c === 0x2b) return { t: 'repeat', node: atom, min: 1, max: -1 }
    if (c === 0x3f) return { t: 'repeat', node: atom, min: 0, max: 1 }
    const min = this.number()
    let max = min
    if (this.pos < this.end && this.cps[this.pos] === 0x2c) {
      this.pos++
      max = this.pos < this.end && this.cps[this.pos] === 0x7d ? -1 : this.number()
    }
    if (this.pos >= this.end || this.cps[this.pos] !== 0x7d) throw badBrace()
    this.pos++
    if (min > MAX_REPEAT || max > MAX_REPEAT) throw new PatternError(`Repeat counts are limited to ${MAX_REPEAT}.`)
    if (max !== -1 && max < min) throw new PatternError('In a repeat count {n,m}, n cannot be larger than m.')
    return { t: 'repeat', node: atom, min, max }
  }

  private number(): number {
    const start = this.pos
    let value = 0
    while (this.pos < this.end && this.cps[this.pos]! >= 0x30 && this.cps[this.pos]! <= 0x39) {
      value = Math.min(value * 10 + (this.cps[this.pos]! - 0x30), 2147483647)
      this.pos++
    }
    if (this.pos === start) throw badBrace()
    return value
  }

  private parseAtom(): Node {
    const c = this.cps[this.pos]!
    switch (c) {
      case 0x28: {
        this.pos++
        if (this.pos < this.end && this.cps[this.pos] === 0x3f) {
          if (this.pos + 1 < this.end && this.cps[this.pos + 1] === 0x3a) this.pos += 2
          else throw new PatternError('Lookarounds, named groups and inline flags (?...) are not supported; use (...) or (?:...).')
        }
        if (++this.depth > MAX_GROUP_DEPTH) throw new PatternError('Groups are nested too deeply.')
        const inner = this.parseAlt()
        this.depth--
        if (this.pos >= this.end || this.cps[this.pos] !== 0x29) throw new PatternError('A group ( is not closed.')
        this.pos++
        return inner
      }
      case 0x5b:
        this.pos++
        return { t: 'chars', pred: this.parseClass() }
      case 0x2e:
        this.pos++
        return { t: 'chars', pred: { items: [{ ranges: LINE_TERMINATORS, negated: true }], negated: false } }
      case 0x5c: {
        this.pos++
        const escape = this.parseEscape(false)
        return { t: 'chars', pred: typeof escape === 'number' ? single(escape) : { items: [escape], negated: false } }
      }
      case 0x5e: case 0x24:
        throw new PatternError('^ and $ are only allowed at the very start and end of the pattern.')
      case 0x2a: case 0x2b: case 0x3f: case 0x7b:
        throw new PatternError(`Nothing to repeat before ${show(c)}.`)
      case 0x5d: case 0x7d:
        throw new PatternError(`Write a literal ${show(c)} as \\${String.fromCodePoint(c)}.`)
      default:
        checkPrintable(c)
        this.pos++
        return { t: 'chars', pred: single(c) }
    }
  }

  private parseEscape(inClass: boolean): Item | number {
    if (this.pos >= this.end) throw new PatternError('The pattern ends with a backslash.')
    const c = this.cps[this.pos++]!
    switch (String.fromCodePoint(c)) {
      case 'd': return { ranges: DIGIT, negated: false }
      case 'D': return { ranges: DIGIT, negated: true }
      case 'w': return { ranges: WORD, negated: false }
      case 'W': return { ranges: WORD, negated: true }
      case 's': return { ranges: SPACE, negated: false }
      case 'S': return { ranges: SPACE, negated: true }
    }
    if (SYNTAX.includes(String.fromCodePoint(c)) || (inClass && c === 0x2d)) return c
    const hint = c >= 0x31 && c <= 0x39 ? ' Backreferences are not supported.'
      : c === 0x62 || c === 0x42 ? ' Word boundaries are not supported.'
        : c === 0x70 || c === 0x50 ? ' Unicode property escapes are not supported.' : ''
    throw new PatternError(`\\${String.fromCodePoint(c)} is not supported.${hint} Use \\d \\D \\w \\W \\s \\S, or a backslash before one of ^ $ \\ . * + ? ( ) [ ] { } | /${inClass ? ' -' : ''}.`)
  }

  private parseClass(): Pred {
    let negated = false
    if (this.pos < this.end && this.cps[this.pos] === 0x5e) { negated = true; this.pos++ }
    const items: Item[] = []
    const literal: Array<[number, number]> = []
    let first = true
    for (;;) {
      if (this.pos >= this.end) throw new PatternError('A character class [ is not closed.')
      if (this.cps[this.pos] === 0x5d) {
        if (first) throw new PatternError('A character class [] must list at least one character.')
        this.pos++
        break
      }
      const low = this.classAtom(first)
      first = false
      if (this.pos < this.end && this.cps[this.pos] === 0x2d && this.pos + 1 < this.end && this.cps[this.pos + 1] !== 0x5d) {
        if (typeof low !== 'number') throw new PatternError('A range cannot start or end with \\d, \\w or \\s.')
        this.pos++
        const high = this.classAtom(false)
        if (typeof high !== 'number') throw new PatternError('A range cannot start or end with \\d, \\w or \\s.')
        if (high < low) throw new PatternError(`The range ${show(low)}-${show(high)} is out of order.`)
        literal.push([low, high])
      } else if (typeof low !== 'number') items.push(low)
      else literal.push([low, low])
    }
    if (literal.length > 0) {
      literal.sort((a, b) => a[0] - b[0])
      items.unshift({ ranges: literal.flat(), negated: false })
    }
    return { items, negated }
  }

  private classAtom(first: boolean): Item | number {
    const c = this.cps[this.pos]!
    if (c === 0x5c) { this.pos++; return this.parseEscape(true) }
    if (c === 0x5b) throw new PatternError('Write a literal [ inside a class as \\[.')
    if (c === 0x2d && !first && this.pos + 1 < this.end && this.cps[this.pos + 1] !== 0x5d) throw new PatternError('Inside a class, put - first or last, or write it as \\-.')
    checkPrintable(c)
    this.pos++
    return c
  }
}

const isQuantifierStart = (c: number) => c === 0x2a || c === 0x2b || c === 0x3f || c === 0x7b
const badBrace = () => new PatternError('Write repeat counts as {n}, {n,} or {n,m}; write a literal { as \\{.')
function checkPrintable(c: number) {
  if (c < 0x20 || c === 0x7f) throw new PatternError('Control characters are not allowed in a pattern; use \\s for white space.')
}

const CHAR = 0, SPLIT = 1, MATCH = 2

class Builder {
  kinds: number[] = []
  outs1: number[] = []
  outs2: number[] = []
  preds: Array<Pred | null> = []
  private steps = 0

  state(kind: number, out1: number, out2: number, pred: Pred | null): number {
    if (this.kinds.length >= MAX_STATES) throw new PatternError('The pattern is too complex: repeat counts multiply. Use smaller repeat counts.')
    this.kinds.push(kind); this.outs1.push(out1); this.outs2.push(out2); this.preds.push(pred)
    return this.kinds.length - 1
  }

  compile(node: Node, next: number): number {
    if (++this.steps > MAX_BUILD_STEPS) throw new PatternError('The pattern is too complex: repeat counts multiply. Use smaller repeat counts.')
    switch (node.t) {
      case 'chars': return this.state(CHAR, next, -1, node.pred)
      case 'seq': {
        let entry = next
        for (let i = node.items.length - 1; i >= 0; i--) entry = this.compile(node.items[i]!, entry)
        return entry
      }
      case 'alt': {
        let entry = this.compile(node.branches[node.branches.length - 1]!, next)
        for (let i = node.branches.length - 2; i >= 0; i--) {
          const branch = this.compile(node.branches[i]!, next)
          entry = this.state(SPLIT, branch, entry, null)
        }
        return entry
      }
      case 'repeat': {
        let entry: number
        if (node.max === -1) {
          const loop = this.state(SPLIT, -1, next, null)
          this.outs1[loop] = this.compile(node.node, loop)
          entry = loop
        } else {
          entry = next
          for (let i = 0; i < node.max - node.min; i++) {
            const body = this.compile(node.node, entry)
            entry = this.state(SPLIT, body, next, null)
          }
        }
        for (let i = 0; i < node.min; i++) entry = this.compile(node.node, entry)
        return entry
      }
    }
  }
}

const TOO_COMPLEX = Symbol('too complex')

export class FormPattern {
  readonly source: string
  private readonly kind: number[]
  private readonly out1: number[]
  private readonly out2: number[]
  private readonly preds: Array<Pred | null>
  private readonly start: number

  private constructor(source: string, kind: number[], out1: number[], out2: number[], preds: Array<Pred | null>, start: number) {
    this.source = source; this.kind = kind; this.out1 = out1; this.out2 = out2; this.preds = preds; this.start = start
  }

  /** Throws PatternError with the reason when the pattern cannot be used. */
  static compile(source: string): FormPattern {
    if (!source) throw new PatternError('The pattern is empty.')
    const cps = Array.from(source, ch => ch.codePointAt(0)!)
    if (cps.length > MAX_PATTERN_LENGTH) throw new PatternError(`The pattern is longer than ${MAX_PATTERN_LENGTH} characters.`)
    const tree = new Parser(cps).parsePattern()
    const builder = new Builder()
    const match = builder.state(MATCH, -1, -1, null)
    const start = builder.compile(tree, match)
    const pattern = new FormPattern(source, builder.kinds, builder.outs1, builder.outs2, builder.preds, start)
    const example = pattern.search()
    if (example === TOO_COMPLEX) throw new PatternError('The pattern is too complex to check. Simplify it.')
    if (example === null) throw new PatternError('No answer can match this pattern.')
    return pattern
  }

  /** The reason the pattern cannot be used, or undefined when it compiles. */
  static problem(source: string): string | undefined {
    try { FormPattern.compile(source); return undefined } catch (e) { if (e instanceof PatternError) return e.message; throw e }
  }

  /** True when the whole answer matches. Answers longer than MAX_INPUT_LENGTH code points never match. */
  matches(input: string): boolean {
    const cps = Array.from(input, ch => ch.codePointAt(0)!)
    if (cps.length > MAX_INPUT_LENGTH) return false
    const n = this.kind.length
    const marks = new Int32Array(n)
    let current = new Int32Array(n), next = new Int32Array(n)
    const stack = new Int32Array(n * 2 + 2)
    let generation = 1
    let currentSize = this.add(current, 0, this.start, marks, generation, stack)
    for (const cp of cps) {
      generation++
      let nextSize = 0
      for (let k = 0; k < currentSize; k++) {
        const s = current[k]!
        if (this.kind[s] === CHAR && predMatches(this.preds[s]!, cp)) nextSize = this.add(next, nextSize, this.out1[s]!, marks, generation, stack)
      }
      if (nextSize === 0) return false
      const swap = current; current = next; next = swap
      currentSize = nextSize
    }
    for (let k = 0; k < currentSize; k++) if (this.kind[current[k]!] === MATCH) return true
    return false
  }

  private add(list: Int32Array, size: number, state: number, marks: Int32Array, generation: number, stack: Int32Array): number {
    let top = 0
    stack[top++] = state
    while (top > 0) {
      const s = stack[--top]!
      if (marks[s] === generation) continue
      marks[s] = generation
      if (this.kind[s] === SPLIT) { stack[top++] = this.out2[s]!; stack[top++] = this.out1[s]! } else list[size++] = s
    }
    return size
  }

  private closure(states: number[]): number[] {
    const n = this.kind.length
    const marks = new Int32Array(n), list = new Int32Array(n), stack = new Int32Array(n * 2 + 2)
    let size = 0
    for (const s of states) size = this.add(list, size, s, marks, 1, stack)
    return Array.from(list.slice(0, size)).sort((a, b) => a - b)
  }

  /** A shortest matching answer built from readable characters, or null when none matches. */
  example(): string | null {
    const result = this.search()
    return result === TOO_COMPLEX ? null : result
  }

  private search(): string | null | typeof TOO_COMPLEX {
    const first = this.closure([this.start])
    const queue: Array<{ states: number[]; text: string; length: number }> = [{ states: first, text: '', length: 0 }]
    const seen = new Set<string>([first.join(',')])
    for (let head = 0; head < queue.length; head++) {
      const entry = queue[head]!
      if (entry.states.some(s => this.kind[s] === MATCH)) return entry.text
      if (entry.length >= MAX_INPUT_LENGTH) continue
      const candidates = new Set<number>()
      for (const s of entry.states) if (this.kind[s] === CHAR) { const c = representative(this.preds[s]!); if (c >= 0) candidates.add(c) }
      for (const c of [...candidates].sort((a, b) => a - b)) {
        const targets = entry.states.filter(s => this.kind[s] === CHAR && predMatches(this.preds[s]!, c)).map(s => this.out1[s]!)
        const next = this.closure(targets)
        if (seen.size >= MAX_EXAMPLE_SETS) return TOO_COMPLEX
        const key = next.join(',')
        if (!seen.has(key)) { seen.add(key); queue.push({ states: next, text: entry.text + String.fromCodePoint(c), length: entry.length + 1 }) }
      }
    }
    return null
  }
}

const cache = new Map<string, FormPattern | null>()

/** Compiled pattern (cached), or null when it cannot be used: such an answer never matches (fail closed). */
export function compiledPattern(source: string): FormPattern | null {
  if (!cache.has(source)) {
    if (cache.size >= 256) cache.clear()
    let compiled: FormPattern | null = null
    try { compiled = FormPattern.compile(source) } catch (e) { if (!(e instanceof PatternError)) throw e }
    cache.set(source, compiled)
  }
  return cache.get(source)!
}

export function patternMatches(source: string, text: string): boolean {
  return compiledPattern(source)?.matches(text) ?? false
}

export const DEFAULT_PATTERN_MESSAGE = 'Enter the answer in the expected format.'
