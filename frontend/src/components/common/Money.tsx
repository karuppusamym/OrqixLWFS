import { formatUsd } from '@/lib/money'

/** USD amount. Display only; the UI never computes with amounts. */
export function Money({ value }: { value: number | string | null | undefined }) {
  return <span className="tabular-nums">{formatUsd(value)}</span>
}
