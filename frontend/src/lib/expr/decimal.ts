/**
 * Exact decimals for the Orqix expression engine (docs/build/S137), the browser twin of java.math.BigDecimal as the
 * Java engine uses it: value = u × 10^-s. Arithmetic results are rounded to 34 significant digits, half to even
 * (MathContext.DECIMAL128). No dependency: BigInt only.
 */

export type RoundingMode = 'halfUp' | 'halfEven' | 'down' | 'up' | 'floor' | 'ceiling'

const PRECISION = 34

export class Dec {
  readonly u: bigint
  readonly s: number
  constructor(u: bigint, s: number) {
    this.u = u
    this.s = s
  }
}

const TEN = 10n
const pow10 = (n: number): bigint => TEN ** BigInt(n)
const abs = (n: bigint): bigint => (n < 0n ? -n : n)
const digits = (n: bigint): number => (n === 0n ? 1 : abs(n).toString().length)

export const ZERO = new Dec(0n, 0)

const DECIMAL_TEXT = /^-?[0-9]+(\.[0-9]+)?$/

/** @returns the exact decimal of `-?digits(.digits)?`, or null */
export function parseDec(text: string): Dec | null {
  if (!DECIMAL_TEXT.test(text)) return null
  const neg = text.startsWith('-')
  const body = neg ? text.slice(1) : text
  const dot = body.indexOf('.')
  const s = dot < 0 ? 0 : body.length - dot - 1
  const u = BigInt(dot < 0 ? body : body.slice(0, dot) + body.slice(dot + 1))
  return new Dec(neg ? -u : u, s)
}

/** The decimal a JSON number denotes: its shortest round-trip text, as Java's Double.toString gives (JDK 19+). */
export function fromNumber(n: number): Dec | null {
  if (!Number.isFinite(n)) return null
  if (Number.isInteger(n) && Math.abs(n) < 1e21) return new Dec(BigInt(n), 0)
  const text = String(n)
  const e = text.toLowerCase().indexOf('e')
  if (e < 0) return parseDec(text)
  const mantissa = parseDec(text.slice(0, e))
  if (!mantissa) return null
  const exponent = Number(text.slice(e + 1))
  return new Dec(mantissa.u, mantissa.s - exponent)
}

/** Integer division of n by d > 0 with a rounding mode. */
export function divRound(n: bigint, d: bigint, mode: RoundingMode): bigint {
  const neg = n < 0n
  const a = neg ? -n : n
  let q = a / d
  const r = a % d
  if (r !== 0n) {
    const twice = 2n * r
    let up: boolean
    switch (mode) {
      case 'down': up = false; break
      case 'up': up = true; break
      case 'halfUp': up = twice >= d; break
      case 'halfEven': up = twice > d || (twice === d && q % 2n === 1n); break
      case 'floor': up = neg; break
      case 'ceiling': up = !neg; break
    }
    if (up) q += 1n
  }
  return neg ? -q : q
}

/** Rounds to 34 significant digits, half to even (MathContext.DECIMAL128). */
export function round34(d: Dec): Dec {
  let { u, s } = d
  for (;;) {
    const n = digits(u)
    if (n <= PRECISION) return new Dec(u, s)
    const drop = n - PRECISION
    u = divRound(u, pow10(drop), 'halfEven')
    s -= drop
  }
}

function align(a: Dec, b: Dec): [bigint, bigint, number] {
  const s = Math.max(a.s, b.s)
  return [a.u * pow10(s - a.s), b.u * pow10(s - b.s), s]
}

export const add = (a: Dec, b: Dec): Dec => { const [x, y, s] = align(a, b); return round34(new Dec(x + y, s)) }
export const sub = (a: Dec, b: Dec): Dec => { const [x, y, s] = align(a, b); return round34(new Dec(x - y, s)) }
export const mul = (a: Dec, b: Dec): Dec => round34(new Dec(a.u * b.u, a.s + b.s))
export const neg = (a: Dec): Dec => new Dec(-a.u, a.s)
export const absDec = (a: Dec): Dec => new Dec(abs(a.u), a.s)
export const isZero = (a: Dec): boolean => a.u === 0n

export function cmp(a: Dec, b: Dec): number {
  const [x, y] = align(a, b)
  return x < y ? -1 : x > y ? 1 : 0
}

/** a / b rounded to 34 significant digits, half to even; null when b is zero. */
export function div(a: Dec, b: Dec): Dec | null {
  if (b.u === 0n) return null
  if (a.u === 0n) return ZERO
  const negative = (a.u < 0n) !== (b.u < 0n)
  const A = abs(a.u)
  const B = abs(b.u)
  const k = Math.max(0, PRECISION + 1 + digits(B) - digits(A))
  const num = A * pow10(k)
  const q = num / B
  const r = num % B
  const drop = digits(q) - PRECISION
  const p = pow10(drop)
  let q2 = q / p
  const rem = q % p
  const half = 2n * (rem * B + r) - p * B
  if (half > 0n || (half === 0n && q2 % 2n === 1n)) q2 += 1n
  return round34(new Dec(negative ? -q2 : q2, k + a.s - b.s - drop))
}

/** BigDecimal.setScale(places, mode). */
export function setScale(a: Dec, places: number, mode: RoundingMode): Dec {
  if (places >= a.s) return new Dec(a.u * pow10(places - a.s), places)
  return new Dec(divRound(a.u, pow10(a.s - places), mode), places)
}

/** @returns the whole number a decimal holds, or null when it has a fraction or is beyond ±1e9 */
export function whole(a: unknown): number | null {
  if (!(a instanceof Dec)) return null
  const stripped = strip(a)
  if (stripped.s > 0) return null
  const n = stripped.u * pow10(-stripped.s)
  if (abs(n) > 1_000_000_000n) return null
  return Number(n)
}

function strip(a: Dec): Dec {
  let { u, s } = a
  if (u === 0n) return ZERO
  while (u % TEN === 0n) { u /= TEN; s -= 1 }
  return new Dec(u, s)
}

/** Canonical text: exact, no exponent, no trailing zeros (Java stripTrailingZeros().toPlainString()). */
export function plain(a: Dec): string {
  const { u, s } = strip(a)
  if (u === 0n) return '0'
  if (s <= 0) return u.toString() + '0'.repeat(-s)
  const sign = u < 0n ? '-' : ''
  const text = abs(u).toString().padStart(s + 1, '0')
  return `${sign}${text.slice(0, text.length - s)}.${text.slice(text.length - s)}`
}

export const toNumber = (a: Dec): number => Number(plain(a))
export const decOf = (n: number): Dec => fromNumber(n) ?? ZERO
