import { readFileSync } from 'node:fs'
import path from 'node:path'

/** True when the specs run against the frontend's in-page mock API instead of the real stack. */
export const MOCK = process.env.E2E_MOCK === 'true'

export const BASE_URL = process.env.E2E_BASE_URL ?? (MOCK ? 'http://localhost:4173' : 'http://localhost:8080')

/** Users of the dev realm (docs/build/S1-engineering-baseline.md section 6.1). */
export type Username =
  | 'pat.platform'
  | 'wade.admin'
  | 'dana.designer'
  | 'paul.publisher'
  | 'olga.operator'
  | 'vic.viewer'
  | 'rhea.requester'
  | 'mani.manager'
  | 'max.manager'
  | 'fin.finance'
  | 'fred.facilities'
  | 'otto.outsider'

/** "rhea.requester" -> "E2E_PASSWORD_RHEA_REQUESTER" */
export function passwordVariable(username: string): string {
  return `E2E_PASSWORD_${username.toUpperCase().replace(/[^A-Z0-9]/g, '_')}`
}

let realmPasswords: Record<string, string> | null = null

function fromRealmFile(username: string): string | undefined {
  const file = process.env.E2E_REALM_FILE
  if (!file) return undefined
  if (!realmPasswords) {
    const realm = JSON.parse(readFileSync(path.resolve(file), 'utf8')) as { users?: Array<{ username: string; credentials?: Array<{ type: string; value?: string }> }> }
    realmPasswords = {}
    for (const u of realm.users ?? []) {
      const pw = u.credentials?.find((c) => c.type === 'password')?.value
      if (pw) realmPasswords[u.username] = pw
    }
  }
  return realmPasswords[username]
}

/**
 * Password for a dev-realm user. Never hard-coded. Order:
 *   1. E2E_PASSWORD_<USERNAME>  (for example E2E_PASSWORD_RHEA_REQUESTER)
 *   2. E2E_PASSWORD             (one shared value for every user)
 *   3. the realm file named by E2E_REALM_FILE (convenience for local runs, for example
 *      ../../deploy/compose/keycloak/orqix-dev-realm.json)
 */
export function passwordFor(username: string): string {
  const value = process.env[passwordVariable(username)] ?? process.env.E2E_PASSWORD ?? fromRealmFile(username)
  if (!value) {
    throw new Error(`No password for ${username}. Set ${passwordVariable(username)} (or E2E_PASSWORD, or E2E_REALM_FILE). See tests/e2e/README.md.`)
  }
  return value
}

/** Optional procurement stub admin URL for fault-injection steps in the real stack (docs/build/S1 section 6.2). */
export const STUB_URL = process.env.E2E_STUB_URL
