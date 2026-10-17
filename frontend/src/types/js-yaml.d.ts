/** The small part of js-yaml 4 the studio source view uses (the package ships no types). */
declare module 'js-yaml' {
  export const JSON_SCHEMA: unknown
  export function dump(value: unknown, options?: { schema?: unknown; noRefs?: boolean; lineWidth?: number; sortKeys?: boolean; noCompatMode?: boolean; indent?: number }): string
  export function load(text: string, options?: { schema?: unknown }): unknown
}
