import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const here = path.dirname(fileURLToPath(import.meta.url))
export default defineConfig(({ mode }) => {
  if (!['authoring', 'tasks'].includes(mode)) throw new Error('Choose the authoring or tasks component.')
  return {
    plugins: [react(), tailwindcss()], resolve: { alias: { '@': path.resolve(here, 'src') } },
    // Library mode leaves Node environment checks intact unless explicitly replaced.
    // These artifacts run directly in browsers without a host bundler or a process global.
    define: { __ORQIX_MOCK__: 'false', 'process.env.NODE_ENV': JSON.stringify('production') },
    build: { outDir: `dist-embed/${mode}`, lib: { entry: path.resolve(here, `src/embed/${mode}.tsx`), formats: ['es'], fileName: `orqix-${mode}`, cssFileName: 'orqix' } },
  }
})
