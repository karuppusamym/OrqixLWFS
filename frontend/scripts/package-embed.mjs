import { cp, mkdir, access } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
for (const mode of ['authoring', 'tasks']) {
  await access(path.join(frontend, `dist-embed/${mode}/orqix-${mode}.js`))
  await mkdir(path.join(frontend, `dist/components/${mode}`), { recursive: true })
  await cp(path.join(frontend, `dist-embed/${mode}`), path.join(frontend, `dist/components/${mode}`), { recursive: true })
}
await cp(path.join(frontend, 'embed-host'), path.join(frontend, 'dist/embed-host'), { recursive: true })
console.log('Packaged independent authoring/task builds and the same-origin example host.')
