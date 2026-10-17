// Regenerates the API types into a temporary file and fails when the committed
// src/api/schema.d.ts differs (drift between contracts/openapi.yaml and the client).
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const committed = path.join(root, 'src/api/schema.d.ts')
const dir = mkdtempSync(path.join(tmpdir(), 'orqix-api-'))
const generated = path.join(dir, 'schema.d.ts')
try {
  const bin = path.join(root, 'node_modules/openapi-typescript/bin/cli.js')
  const result = spawnSync(process.execPath, [bin, '../contracts/openapi.yaml', '-o', generated], {
    cwd: root,
    stdio: ['ignore', 'ignore', 'inherit'],
  })
  if (result.status !== 0) {
    console.error('api:check failed: openapi-typescript exited with', result.status)
    process.exit(1)
  }
  const normalize = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
  if (normalize(committed) !== normalize(generated)) {
    console.error('api:check failed: src/api/schema.d.ts is out of date. Run `npm run api:generate` and commit the result.')
    process.exit(1)
  }
  console.log('api:check ok: src/api/schema.d.ts matches contracts/openapi.yaml')
} finally {
  rmSync(dir, { recursive: true, force: true })
}
