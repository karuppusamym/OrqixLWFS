/**
 * Lexer and parser of Orqix expressions (docs/build/S137): a line-by-line port of
 * backend/compiler/src/main/java/com/orqix/compiler/expr/Parser.java, so both report the same codes at the same offsets.
 */
import { type Dec, parseDec } from './decimal'
import { ExpressionError } from './types'

export const MAX_SOURCE = 2000
export const MAX_NODES = 300
export const MAX_DEPTH = 30
export const MAX_LIST = 100
export const MAX_ARGS = 10
export const MAX_DIGITS = 30

const KEYWORDS = new Set(['if', 'then', 'else', 'and', 'or', 'true', 'false', 'null', 'in'])

export type Op = '+' | '-' | '*' | '/' | '=' | '!=' | '<' | '<=' | '>' | '>=' | 'and' | 'or' | 'in'
export type RefKind = 'input' | 'step' | 'item' | 'local'

export type Ast =
  | { t: 'num'; pos: number; value: Dec }
  | { t: 'str'; pos: number; value: string }
  | { t: 'bool'; pos: number; value: boolean }
  | { t: 'null'; pos: number }
  | { t: 'list'; pos: number; items: Ast[] }
  | { t: 'ref'; pos: number; kind: RefKind; step?: string; key?: string }
  | { t: 'member'; pos: number; target: Ast; name: string }
  | { t: 'neg'; pos: number; operand: Ast }
  | { t: 'binary'; pos: number; op: Op; left: Ast; right: Ast }
  | { t: 'if'; pos: number; condition: Ast; then: Ast; otherwise: Ast }
  | { t: 'call'; pos: number; name: string; args: Ast[] }

type Kind = 'num' | 'str' | 'ident' | 'sym' | 'eof'
interface Token { kind: Kind; text: string; pos: number; spaceBefore: boolean }

const describe = (t: Token) => (t.kind === 'eof' ? 'the end' : `"${t.text}"`)
const digit = (c: string) => c >= '0' && c <= '9'
const identStart = (c: string) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_'
const identPart = (c: string) => identStart(c) || digit(c)

export function lex(s: string): Token[] {
  const out: Token[] = []
  let i = 0
  let space = false
  while (i < s.length) {
    const c = s[i]!
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
      i++
      space = true
      continue
    }
    const start = i
    if (digit(c)) {
      while (i < s.length && digit(s[i]!)) i++
      if (i + 1 < s.length && s[i] === '.' && digit(s[i + 1]!)) {
        i++
        while (i < s.length && digit(s[i]!)) i++
      }
      const text = s.slice(start, i)
      if (text.replace('.', '').length > MAX_DIGITS) throw new ExpressionError('EXPR_SYNTAX', start, `A number can have at most ${MAX_DIGITS} digits.`)
      if (i < s.length && identStart(s[i]!)) throw new ExpressionError('EXPR_SYNTAX', i, `Put a space or an operator after the number ${text}.`)
      out.push({ kind: 'num', text, pos: start, spaceBefore: space })
    } else if (identStart(c)) {
      while (i < s.length && identPart(s[i]!)) i++
      out.push({ kind: 'ident', text: s.slice(start, i), pos: start, spaceBefore: space })
    } else if (c === '"') {
      let b = ''
      i++
      for (;;) {
        if (i >= s.length) throw new ExpressionError('EXPR_SYNTAX', start, 'This text is missing its closing quote.')
        const d = s[i]!
        if (d === '"') {
          i++
          break
        }
        if (d === '\n' || d === '\r') throw new ExpressionError('EXPR_SYNTAX', i, 'Text cannot run over several lines; write \\n for a new line.')
        if (d === '\\') {
          const e = i + 1 < s.length ? s[i + 1] : ''
          if (e === '"') b += '"'
          else if (e === '\\') b += '\\'
          else if (e === 'n') b += '\n'
          else if (e === 't') b += '\t'
          else throw new ExpressionError('EXPR_SYNTAX', i, 'Only \\", \\\\, \\n and \\t can follow a backslash.')
          i += 2
          continue
        }
        b += d
        i++
      }
      out.push({ kind: 'str', text: b, pos: start, spaceBefore: space })
    } else {
      const two = i + 1 < s.length ? s.slice(i, i + 2) : ''
      if (two === '!=' || two === '<=' || two === '>=') {
        out.push({ kind: 'sym', text: two, pos: start, spaceBefore: space })
        i += 2
      } else if (two === '==') {
        throw new ExpressionError('EXPR_SYNTAX', start, 'Use a single = to compare.')
      } else if ('+-*/()[],.=<>'.includes(c)) {
        out.push({ kind: 'sym', text: c, pos: start, spaceBefore: space })
        i++
      } else {
        throw new ExpressionError('EXPR_SYNTAX', start, `The character "${String.fromCodePoint(s.codePointAt(i)!)}" is not allowed here.`)
      }
    }
    space = false
  }
  out.push({ kind: 'eof', text: '', pos: s.length, spaceBefore: space })
  return out
}

const COMPARISONS: Record<string, Op> = { '=': '=', '!=': '!=', '<': '<', '<=': '<=', '>': '>', '>=': '>=' }

class Parser {
  private at = 0
  private nodes = 0
  private depth = 0
  private readonly tokens: Token[]
  constructor(tokens: Token[]) {
    this.tokens = tokens
  }

  peek(ahead = 0): Token {
    return this.tokens[Math.min(this.at + ahead, this.tokens.length - 1)]!
  }

  next(): Token {
    const t = this.tokens[this.at]!
    if (t.kind !== 'eof') this.at++
    return t
  }

  private sym(text: string): boolean {
    const t = this.peek()
    return t.kind === 'sym' && t.text === text
  }

  private word(text: string): boolean {
    const t = this.peek()
    return t.kind === 'ident' && t.text === text
  }

  private expectSym(text: string): Token {
    if (!this.sym(text)) {
      const t = this.peek()
      throw new ExpressionError('EXPR_SYNTAX', t.pos, `Expected "${text}" but found ${describe(t)}.`)
    }
    return this.next()
  }

  private expectWord(text: string): void {
    if (!this.word(text)) {
      const t = this.peek()
      throw new ExpressionError('EXPR_SYNTAX', t.pos, `Expected "${text}" but found ${describe(t)}.`)
    }
    this.next()
  }

  private name(): string {
    const t = this.peek()
    if (t.kind !== 'ident') throw new ExpressionError('EXPR_SYNTAX', t.pos, `Expected a name but found ${describe(t)}.`)
    return this.next().text
  }

  private node<T extends Ast>(ast: T): T {
    if (++this.nodes > MAX_NODES) throw new ExpressionError('EXPR_TOO_COMPLEX', ast.pos, 'This expression is too complex: split it into several outputs.')
    return ast
  }

  private enter(pos: number): void {
    if (++this.depth > MAX_DEPTH) throw new ExpressionError('EXPR_TOO_COMPLEX', pos, 'This expression is nested too deeply.')
  }

  expr(): Ast {
    this.enter(this.peek().pos)
    let left = this.and()
    while (this.word('or')) {
      const op = this.next()
      left = this.node({ t: 'binary', pos: op.pos, op: 'or', left, right: this.and() })
    }
    this.depth--
    return left
  }

  private and(): Ast {
    let left = this.comparison()
    while (this.word('and')) {
      const op = this.next()
      left = this.node({ t: 'binary', pos: op.pos, op: 'and', left, right: this.comparison() })
    }
    return left
  }

  private comparison(): Ast {
    const left = this.additive()
    const t = this.peek()
    let op: Op | null = null
    if (t.kind === 'sym') op = COMPARISONS[t.text] ?? null
    else if (this.word('in')) op = 'in'
    if (op === null) return left
    this.next()
    const result = this.node({ t: 'binary', pos: t.pos, op, left, right: this.additive() })
    const after = this.peek()
    if ((after.kind === 'sym' && after.text in COMPARISONS) || this.word('in')) {
      throw new ExpressionError('EXPR_SYNTAX', after.pos, 'Compare two values at a time; join comparisons with and.')
    }
    return result
  }

  private additive(): Ast {
    let left = this.multiplicative()
    while (this.sym('+') || this.sym('-')) {
      const op = this.next()
      left = this.node({ t: 'binary', pos: op.pos, op: op.text === '+' ? '+' : '-', left, right: this.multiplicative() })
    }
    return left
  }

  private multiplicative(): Ast {
    let left = this.unary()
    while (this.sym('*') || this.sym('/')) {
      const op = this.next()
      left = this.node({ t: 'binary', pos: op.pos, op: op.text === '*' ? '*' : '/', left, right: this.unary() })
    }
    return left
  }

  private unary(): Ast {
    if (this.sym('-')) {
      const op = this.next()
      this.enter(op.pos)
      const operand = this.unary()
      this.depth--
      return this.node({ t: 'neg', pos: op.pos, operand })
    }
    return this.postfix()
  }

  private postfix(): Ast {
    let target = this.primary()
    while (this.sym('.')) {
      const dot = this.next()
      target = this.node({ t: 'member', pos: dot.pos, target, name: this.name() })
    }
    return target
  }

  private primary(): Ast {
    const t = this.peek()
    switch (t.kind) {
      case 'num':
        this.next()
        return this.node({ t: 'num', pos: t.pos, value: parseDec(t.text)! })
      case 'str':
        this.next()
        return this.node({ t: 'str', pos: t.pos, value: t.text })
      case 'sym': {
        if (t.text === '(') {
          this.next()
          const inner = this.expr()
          this.expectSym(')')
          return inner
        }
        if (t.text === '[') {
          this.next()
          const items: Ast[] = []
          if (!this.sym(']')) {
            do {
              if (items.length === MAX_LIST) throw new ExpressionError('EXPR_TOO_COMPLEX', this.peek().pos, `A list can have at most ${MAX_LIST} values.`)
              items.push(this.expr())
            } while (this.sym(',') && this.next())
          }
          this.expectSym(']')
          return this.node({ t: 'list', pos: t.pos, items })
        }
        throw new ExpressionError('EXPR_SYNTAX', t.pos, `Expected a value but found ${describe(t)}.`)
      }
      case 'eof':
        throw new ExpressionError('EXPR_SYNTAX', t.pos, 'The expression ends too early.')
      default:
        return this.wordAst(t)
    }
  }

  private wordAst(t: Token): Ast {
    const w = t.text
    if (w === 'true' || w === 'false') {
      this.next()
      return this.node({ t: 'bool', pos: t.pos, value: w === 'true' })
    }
    if (w === 'null') {
      this.next()
      return this.node({ t: 'null', pos: t.pos })
    }
    if (w === 'if') {
      this.next()
      const condition = this.expr()
      this.expectWord('then')
      const then = this.expr()
      this.expectWord('else')
      const otherwise = this.expr()
      return this.node({ t: 'if', pos: t.pos, condition, then, otherwise })
    }
    if (KEYWORDS.has(w)) throw new ExpressionError('EXPR_SYNTAX', t.pos, `Expected a value but found ${describe(t)}.`)
    this.next()
    if (this.sym('(')) {
      this.next()
      const args: Ast[] = []
      if (!this.sym(')')) {
        do {
          if (args.length === MAX_ARGS) throw new ExpressionError('EXPR_ARGUMENTS', this.peek().pos, `${w} takes at most ${MAX_ARGS} values.`)
          args.push(this.expr())
        } while (this.sym(',') && this.next())
      }
      this.expectSym(')')
      return this.node({ t: 'call', pos: t.pos, name: w, args })
    }
    if (w === 'input') {
      this.expectSym('.')
      return this.node({ t: 'ref', pos: t.pos, kind: 'input', key: this.name() })
    }
    if (w === 'steps') {
      this.expectSym('.')
      const step = this.stepId()
      this.expectSym('.')
      return this.node({ t: 'ref', pos: t.pos, kind: 'step', step, key: this.name() })
    }
    if (w === 'item') return this.node({ t: 'ref', pos: t.pos, kind: 'item' })
    return this.node({ t: 'ref', pos: t.pos, kind: 'local', key: w })
  }

  /** A step ID may contain hyphens (check-credit); they belong to it only when written without spaces. */
  private stepId(): string {
    let id = this.name()
    while (this.sym('-') && !this.peek().spaceBefore) {
      const part = this.peek(1)
      if (part.spaceBefore || (part.kind !== 'ident' && part.kind !== 'num')) break
      this.next()
      this.next()
      id += `-${part.text}`
    }
    return id
  }
}

export function parse(source: string): Ast {
  if (source == null || source.trim() === '') throw new ExpressionError('EXPR_SYNTAX', 0, 'Write an expression.')
  if (source.length > MAX_SOURCE) throw new ExpressionError('EXPR_TOO_COMPLEX', 0, `An expression can be at most ${MAX_SOURCE} characters long.`)
  const p = new Parser(lex(source))
  const ast = p.expr()
  const t = p.peek()
  if (t.kind !== 'eof') throw new ExpressionError('EXPR_SYNTAX', t.pos, `Unexpected ${describe(t)}.`)
  return ast
}
