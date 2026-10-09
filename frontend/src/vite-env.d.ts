/// <reference types="vite/client" />

/** Compile-time flag set by `--mode mock` or VITE_MOCK_API=true. Dead-code eliminated otherwise. */
declare const __ORQIX_MOCK__: boolean

interface ImportMetaEnv {
  readonly VITE_MOCK_API?: string
  /** Theme: `wf` (default) or `orqix` (teal). */
  readonly VITE_ORQIX_BRAND?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}
