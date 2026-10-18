/** Environment stages in promotion order (PL02, docs/build/S54). */
export const STAGES = ['development', 'test', 'staging', 'production'] as const

export type Stage = (typeof STAGES)[number]

const LABELS: Record<Stage, string> = { development: 'Development', test: 'Test', staging: 'Staging', production: 'Production' }

export const stageLabel = (stage: string): string => LABELS[stage as Stage] ?? stage

export const stageRank = (stage: string): number => STAGES.indexOf(stage as Stage)
