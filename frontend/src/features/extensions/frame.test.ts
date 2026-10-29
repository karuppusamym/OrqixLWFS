import { describe, expect, it } from 'vitest'
import serverBridge from '../../../../backend/app/src/main/resources/ui-extension/bridge.js?raw'
import bridge from './bridge.js?raw'
import { buildFrameDocument, embeddable, parseFrameMessage, PROTOCOL } from './frame'

describe('extension frame protocol (S111)', () => {
  it('uses the same bridge as the server frame document', () => {
    expect(bridge).toBe(serverBridge)
    expect(bridge).toContain(PROTOCOL)
  })

  it('accepts only well-formed bridge messages', () => {
    expect(parseFrameMessage({ protocol: PROTOCOL, type: 'ready' })).toEqual({ protocol: PROTOCOL, type: 'ready' })
    expect(parseFrameMessage({ protocol: PROTOCOL, type: 'action', chainId: 'go', requestId: 'r1' })?.type).toBe('action')
    expect(parseFrameMessage({ protocol: PROTOCOL, type: 'action', chainId: 7, requestId: 'r1' })).toBeNull()
    expect(parseFrameMessage({ protocol: 'other', type: 'ready' })).toBeNull()
    expect(parseFrameMessage({ protocol: PROTOCOL, type: 'eval', code: 'x' })).toBeNull()
    expect(parseFrameMessage({ protocol: PROTOCOL, type: 'resize', height: Number.NaN })).toBeNull()
    expect(parseFrameMessage('ready')).toBeNull()
  })

  it('builds a document whose policy admits only the bridge and bundle by hash and blocks the network', async () => {
    const html = await buildFrameDocument(bridge, "document.body.textContent = 'x';")
    const policy = /content="([^"]+)"/.exec(html)![1]!
    expect(policy).toContain("default-src 'none'")
    expect(policy).toContain("connect-src 'none'")
    expect(policy).toContain("form-action 'none'")
    expect(policy.match(/'sha256-[A-Za-z0-9+/=]+'/g)).toHaveLength(2)
    expect(policy).not.toContain('unsafe-eval')
    expect(embeddable('a</SCRIPT>b')).toBe(false)
    expect(embeddable('a<!-- b')).toBe(false)
    expect(embeddable("orqix.onData(function () {});")).toBe(true)
  })
})
