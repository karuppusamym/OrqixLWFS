import assert from 'node:assert/strict'
import { describe, test } from 'node:test'
import { componentUrls, mountOrqixComponent, type EmbedOptions } from '../src/embed.js'

const WS = '00000000-0000-7000-8000-000000000201'
const WF = '0193a0c4-2222-7000-8000-000000000002'

function fakeDocument(origin = 'https://apps.example') {
  const appended: Array<{ rel: string; href: string }> = []
  const doc = {
    location: { origin },
    head: { appendChild: (node: { rel: string; href: string }) => { appended.push(node); return node } },
    createElement: () => ({ rel: '', href: '' }),
    querySelector: (selector: string) => appended.find(link => selector.includes(`href="${link.href}"`)) ?? null,
  }
  return { doc: doc as unknown as NonNullable<EmbedOptions['document']>, appended }
}

describe('embed helper', () => {
  test('loads the stylesheet once and mounts with the v1 contract', async () => {
    const { doc, appended } = fakeDocument()
    const loaded: string[] = []
    const mounts: unknown[] = []
    const importModule = async (url: string) => {
      loaded.push(url)
      return { mount: (_element: unknown, options: unknown) => { mounts.push(options); return { navigate: () => undefined, unmount: () => undefined } } }
    }
    const element = {} as HTMLElement
    const onNavigate = () => undefined
    await mountOrqixComponent(element, { component: 'authoring', workspaceId: WS, workflowId: WF, onNavigate, document: doc, importModule })
    await mountOrqixComponent(element, { component: 'authoring', workspaceId: WS, document: doc, importModule })
    assert.deepEqual(loaded, ['/components/authoring/orqix-authoring.js', '/components/authoring/orqix-authoring.js'])
    assert.deepEqual(appended.map(l => l.href), ['/components/authoring/orqix.css'])
    assert.deepEqual(mounts[0], { apiVersion: 'v1', workspaceId: WS, workflowId: WF, onNavigate })
    assert.deepEqual(mounts[1], { apiVersion: 'v1', workspaceId: WS })
  })

  test('accepts a path prefix or a same-origin URL and refuses another origin', () => {
    assert.equal(componentUrls('tasks', '/orqix/').module, '/orqix/components/tasks/orqix-tasks.js')
    assert.equal(componentUrls('tasks', 'https://apps.example/orqix', 'https://apps.example').stylesheet, 'https://apps.example/orqix/components/tasks/orqix.css')
    assert.throws(() => componentUrls('tasks', 'https://orqix.other', 'https://apps.example'), /origin/)
    assert.throws(() => componentUrls('tasks', '//orqix.other/x', 'https://apps.example'), /origin/)
    assert.throws(() => componentUrls('tasks', 'relative/path'), TypeError)
    assert.throws(() => componentUrls('tasks', '/a/../b'), TypeError)
    assert.throws(() => componentUrls('studio' as never), TypeError)
  })

  test('refuses malformed IDs before loading anything', async () => {
    const { doc, appended } = fakeDocument()
    let loads = 0
    const importModule = async () => { loads++; return {} }
    await assert.rejects(mountOrqixComponent({} as HTMLElement, { component: 'tasks', workspaceId: 'not-a-uuid', document: doc, importModule }), TypeError)
    await assert.rejects(mountOrqixComponent({} as HTMLElement, { component: 'tasks', workspaceId: WS, workflowId: WF, document: doc, importModule }), TypeError)
    assert.equal(loads, 0)
    assert.equal(appended.length, 0)
  })

  test('a module without mount is refused', async () => {
    const { doc } = fakeDocument()
    await assert.rejects(mountOrqixComponent({} as HTMLElement, { component: 'tasks', workspaceId: WS, document: doc, importModule: async () => ({}) }), /not an Orqix component/)
  })
})
