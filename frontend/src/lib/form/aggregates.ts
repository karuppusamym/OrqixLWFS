/** Resolve only the canonical aggregate paths. Decimal arithmetic uses integer coefficients, never repeated float addition. */
export function resolveInputAggregate(input: Record<string, unknown>, ref: string): number | undefined {
  const match = /^\/input\/([a-z][A-Za-z0-9]{0,63})\/(count|sum(?:\/([a-z][A-Za-z0-9]{0,63})))$/.exec(ref)
  if (!match) return
  const list = input[match[1]!]
  if (list === undefined) return 0
  if (!Array.isArray(list)) throw new Error('The aggregate source must be a collection.')
  if (match[2] === 'count') return list.length
  let coefficient = 0n, scale = 0
  for (const row of list) {
    if (typeof row !== 'object' || row === null || Array.isArray(row)) throw new Error('A row sum requires objects with numeric cells.')
    const cell = (row as Record<string, unknown>)[match[3]!]
    if (cell === undefined) continue
    if (typeof cell !== 'number' || !Number.isFinite(cell)) throw new Error('A row sum requires numeric cells.')
    const parsed = /^(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(String(cell))!
    const digits = (parsed[2]! + (parsed[3] ?? ''))
    const cellScale = (parsed[3]?.length ?? 0) - Number(parsed[4] ?? 0)
    let value = BigInt(digits) * (parsed[1] ? -1n : 1n)
    if (cellScale < 0) value *= 10n ** BigInt(-cellScale)
    const positiveScale = Math.max(0, cellScale)
    const nextScale = Math.max(scale, positiveScale)
    coefficient = coefficient * 10n ** BigInt(nextScale - scale) + value * 10n ** BigInt(nextScale - positiveScale)
    scale = nextScale
  }
  const result = Number(`${coefficient}e-${scale}`)
  if (!Number.isFinite(result)) throw new Error('The sum exceeds the supported numeric range.')
  return result
}
