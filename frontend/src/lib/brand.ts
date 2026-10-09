/**
 * Brand (theme) selection. The two palettes live in src/index.css: `orqix` (teal) is the base, `wf` (red, gold and
 * warm neutrals) overrides it through `:root[data-brand='wf']`. The brand is chosen at build time with
 * VITE_ORQIX_BRAND (default `wf`). vite.config.ts writes it into index.html so the first paint is already correct, and
 * `applyBrand` repeats it at startup (the mock build, tests and a stale cached index.html all end up consistent).
 */
export type Brand = 'wf' | 'orqix'

export const DEFAULT_BRAND: Brand = 'wf'

export function resolveBrand(value: string | null | undefined): Brand {
  return value === 'orqix' || value === 'wf' ? value : DEFAULT_BRAND
}

export function applyBrand(value: string | null | undefined = import.meta.env.VITE_ORQIX_BRAND, root: HTMLElement = document.documentElement): Brand {
  const brand = resolveBrand(value)
  root.dataset.brand = brand
  return brand
}
