/**
 * Embed helper for the Orqix authoring and task components (docs/hackathon-embedding.md). It loads the component's
 * stylesheet and ES module from the Orqix origin and mounts it with the v1 contract.
 *
 * ```ts
 * import { mountOrqixComponent } from '@orqix/sdk/embed'
 * const component = await mountOrqixComponent(document.getElementById('workflow')!, {
 *   component: 'authoring', workspaceId, onNavigate: path => console.info(path),
 * })
 * component.unmount()
 * ```
 *
 * The components use the signed-in application session, so they must be served from the host page's origin (through
 * the application's proxy). The helper refuses a component origin other than the page's. No token, user or role is
 * passed: identity comes from the session.
 */

export type ComponentKind = 'authoring' | 'tasks'

export interface EmbedOptions {
  component: ComponentKind
  /** Workspace UUID. */
  workspaceId: string
  /** Authoring only: open this workflow's draft instead of the catalog. */
  workflowId?: string
  /** Called with the component-internal path on every navigation (also sent as the bubbling `orqix:navigate` event). */
  onNavigate?: (path: string) => void
  /** Where the components are served: a path on this origin (default '') or a same-origin absolute URL. */
  baseUrl?: string
  /** For tests and unusual hosts: the document to add the stylesheet to. */
  document?: Pick<Document, 'createElement' | 'head' | 'querySelector'> & { location?: { origin: string } }
  /** For tests and bundler set-ups: loads the component module. Default: a native dynamic import. */
  importModule?: (url: string) => Promise<unknown>
}

export interface MountedComponent {
  /** Navigates within the selected workspace (paths start with `/w/<workspaceId>/`). */
  navigate(path: string): unknown
  /** Removes the component. */
  unmount(): void
}

interface ComponentModule {
  mount(element: HTMLElement, options: { apiVersion: 'v1'; workspaceId: string; workflowId?: string; onNavigate?: (path: string) => void }): MountedComponent
}

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i

/** The URLs of a component's module and stylesheet. Throws for a component origin other than the page's. */
export function componentUrls(component: ComponentKind, baseUrl = '', pageOrigin?: string): { module: string; stylesheet: string } {
  if (component !== 'authoring' && component !== 'tasks') throw new TypeError('component is authoring or tasks')
  let base = baseUrl.replace(/\/+$/, '')
  if (/^[a-z][a-z0-9+.-]*:/i.test(base) || base.startsWith('//')) {
    const url = new URL(base, pageOrigin ?? 'http://invalid')
    if (pageOrigin === undefined || url.origin !== pageOrigin) {
      throw new TypeError('The Orqix components must be served from this page\'s origin (they use the application session).')
    }
    base = url.origin + url.pathname.replace(/\/+$/, '')
  } else if (base !== '' && !base.startsWith('/')) {
    throw new TypeError('baseUrl is a path on this origin, such as /orqix, or a same-origin URL')
  }
  if (base.includes('..')) throw new TypeError('baseUrl may not contain ..')
  return {
    module: `${base}/components/${component}/orqix-${component}.js`,
    stylesheet: `${base}/components/${component}/orqix.css`,
  }
}

export async function mountOrqixComponent(element: HTMLElement, options: EmbedOptions): Promise<MountedComponent> {
  if (!UUID.test(options.workspaceId ?? '') || (options.workflowId !== undefined && !UUID.test(options.workflowId))) {
    throw new TypeError('A valid workspace/workflow ID is required.')
  }
  if (options.component === 'tasks' && options.workflowId !== undefined) {
    throw new TypeError('workflowId applies to the authoring component only')
  }
  const doc = options.document ?? globalThis.document
  const origin = options.document ? options.document.location?.origin : globalThis.location?.origin
  const urls = componentUrls(options.component, options.baseUrl ?? '', origin)
  if (!doc.querySelector(`link[rel="stylesheet"][href="${urls.stylesheet}"]`)) {
    const link = doc.createElement('link')
    link.rel = 'stylesheet'
    link.href = urls.stylesheet
    doc.head.appendChild(link)
  }
  const load = options.importModule ?? ((url: string) => import(/* @vite-ignore */ /* webpackIgnore: true */ url))
  const module = (await load(urls.module)) as Partial<ComponentModule>
  if (typeof module?.mount !== 'function') throw new Error(`${urls.module} is not an Orqix component`)
  return module.mount(element, {
    apiVersion: 'v1',
    workspaceId: options.workspaceId,
    ...(options.workflowId ? { workflowId: options.workflowId } : {}),
    ...(options.onNavigate ? { onNavigate: options.onNavigate } : {}),
  })
}
