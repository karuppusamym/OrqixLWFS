/** Host side of the S111 extension bridge. The frame side is backend/app/src/main/resources/ui-extension/bridge.js. */
export const PROTOCOL = 'orqix-extension/1'

export type FrameMessage =
  | { protocol: typeof PROTOCOL; type: 'ready' }
  | { protocol: typeof PROTOCOL; type: 'resize'; height: number }
  | { protocol: typeof PROTOCOL; type: 'error'; message: string }
  | { protocol: typeof PROTOCOL; type: 'action'; chainId: string; requestId: string }
  | { protocol: typeof PROTOCOL; type: 'pong'; nonce: string }
  | { protocol: typeof PROTOCOL; type: 'unloading' }

/** Accepts only well-formed bridge messages; everything else from the frame is ignored. */
export function parseFrameMessage(data: unknown): FrameMessage | null {
  if (!data || typeof data !== 'object') return null
  const message = data as Record<string, unknown>
  if (message.protocol !== PROTOCOL || typeof message.type !== 'string') return null
  switch (message.type) {
    case 'ready': return { protocol: PROTOCOL, type: 'ready' }
    case 'resize': return typeof message.height === 'number' && Number.isFinite(message.height) ? { protocol: PROTOCOL, type: 'resize', height: message.height } : null
    case 'error': return { protocol: PROTOCOL, type: 'error', message: typeof message.message === 'string' ? message.message.slice(0, 300) : 'Unknown error' }
    case 'action': return typeof message.chainId === 'string' && typeof message.requestId === 'string' && message.requestId.length <= 32 ? { protocol: PROTOCOL, type: 'action', chainId: message.chainId, requestId: message.requestId } : null
    case 'pong': return typeof message.nonce === 'string' ? { protocol: PROTOCOL, type: 'pong', nonce: message.nonce } : null
    case 'unloading': return { protocol: PROTOCOL, type: 'unloading' }
    default: return null
  }
}

export interface FrameSource { src?: string; srcdoc?: string }

/** The sandboxed frame document is loaded by the iframe itself, never fetched into the page. */
export const extensionFrameUrl = (ws: string, extensionId: string, version: number) => `/api/v1/workspaces/${ws}/ui-extensions/${extensionId}/versions/${version}/frame`

/**
 * Production loads the server document, whose own Content-Security-Policy sandboxes it and admits only the bridge and
 * the approved bundle by hash. Mock mode has no server, so it builds the same document with the policy in a meta tag;
 * the iframe sandbox attribute supplies the opaque origin in both cases.
 */
export async function frameSource(ws: string, extensionId: string, version: number): Promise<FrameSource> {
  if (__ORQIX_MOCK__) {
    const { mockFrameDocument } = await import('@/mocks/extensions')
    return { srcdoc: await mockFrameDocument(ws, extensionId, version) }
  }
  return { src: extensionFrameUrl(ws, extensionId, version) }
}

const STYLE = 'body{margin:0;padding:8px;font:14px/1.45 system-ui,sans-serif;color:CanvasText;background:Canvas}'

/** The iframe `allow` list: the same powerful features the server's Permissions-Policy denies (UiExtensionFrame.PERMISSIONS). */
export const FRAME_ALLOW = ['camera', 'microphone', 'geolocation', 'payment', 'usb', 'fullscreen', 'display-capture', 'clipboard-read', 'clipboard-write', 'publickey-credentials-get', 'autoplay'].map(feature => `${feature} 'none'`).join('; ')

async function hash(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  let binary = ''
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte)
  return btoa(binary)
}

/** Mirrors UiExtensionFrame.build on the server; `sandbox` and `frame-ancestors` are not allowed in a meta policy. */
export async function buildFrameDocument(bridge: string, bundle: string): Promise<string> {
  const policy = `default-src 'none'; script-src 'sha256-${await hash(bridge)}' 'sha256-${await hash(bundle)}'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; manifest-src 'none'; form-action 'none'; base-uri 'none'`
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="referrer" content="no-referrer"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Extension</title><style>${STYLE}</style></head><body><div id="root"></div><script>${bridge}</script><script>${bundle}</script></body></html>`
}

/** Same rule as the server: the bundle cannot close or open markup inside its inline script element. */
export function embeddable(bundle: string): boolean {
  const lower = bundle.toLowerCase()
  return !lower.includes('</script') && !lower.includes('<script') && !lower.includes('<!--')
}
