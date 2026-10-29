import { act, fireEvent, render, screen } from '@testing-library/react'
import axe from 'axe-core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ExtensionHost, type HostResult } from './ExtensionHost'
import { PROTOCOL } from './frame'

function frame(): HTMLIFrameElement {
  return screen.getByTitle('Badge') as HTMLIFrameElement
}

function fromFrame(data: unknown, source: MessageEventSource | null = frame().contentWindow) {
  act(() => { window.dispatchEvent(new MessageEvent('message', { data, source })) })
}

function setup(overrides: Partial<Parameters<typeof ExtensionHost>[0]> = {}) {
  const onRun = vi.fn(async (): Promise<HostResult> => ({ completed: true, steps: [{ action: 'approve', status: 'succeeded' }] }))
  const utils = render(<div><ExtensionHost label="Badge" source={{ srcdoc: '<p>frame</p>' }} data={{ 'task.requestTitle': 'Laptop' }} chains={[{ id: 'approveNow', label: 'Approve' }]} height={160} onRun={onRun} {...overrides} /><button type="button">Rest of the page</button></div>)
  const posted = vi.spyOn(frame().contentWindow!, 'postMessage')
  return { ...utils, onRun, posted }
}

describe('sandboxed extension host (S111)', () => {
  beforeEach(() => { vi.useFakeTimers({ shouldAdvanceTime: true }) })
  afterEach(() => { vi.useRealTimers() })

  it('renders an opaque-origin sandbox and sends only the granted data and chains after the bridge is ready', async () => {
    const { posted } = setup()
    expect(frame().getAttribute('sandbox')).toBe('allow-scripts')
    expect(frame().getAttribute('referrerpolicy')).toBe('no-referrer')
    fromFrame({ protocol: PROTOCOL, type: 'ready' })
    expect(posted).toHaveBeenCalledWith({ protocol: PROTOCOL, type: 'init', data: { 'task.requestTitle': 'Laptop' }, chains: [{ id: 'approveNow', label: 'Approve' }] }, '*')
    fromFrame({ protocol: PROTOCOL, type: 'resize', height: 5000 })
    expect(frame().style.height).toBe('800px')
  })

  it('runs only granted chains through the host callback and ignores messages from other windows', async () => {
    const { onRun, posted } = setup()
    fromFrame({ protocol: PROTOCOL, type: 'ready' })
    fromFrame({ protocol: PROTOCOL, type: 'action', chainId: 'deleteEverything', requestId: 'r1' })
    expect(onRun).not.toHaveBeenCalled()
    expect(posted).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'result', requestId: 'r1', result: expect.objectContaining({ completed: false }) }), '*')
    fromFrame({ protocol: PROTOCOL, type: 'action', chainId: 'approveNow', requestId: 'r2' }, window)
    expect(onRun).not.toHaveBeenCalled()
    fromFrame({ protocol: PROTOCOL, type: 'action', chainId: 'approveNow', requestId: 'r3' })
    expect(onRun).toHaveBeenCalledWith('approveNow')
    await act(async () => { await Promise.resolve() })
    expect(posted).toHaveBeenLastCalledWith({ protocol: PROTOCOL, type: 'result', requestId: 'r3', result: { completed: true, steps: [{ action: 'approve', status: 'succeeded', problemCode: null }] } }, '*')
  })

  it('isolates a crash: the frame is removed, a fallback explains it and the page stays usable', async () => {
    setup()
    fromFrame({ protocol: PROTOCOL, type: 'ready' })
    fromFrame({ protocol: PROTOCOL, type: 'error', message: 'boom' })
    expect(screen.queryByTitle('Badge')).not.toBeInTheDocument()
    expect(screen.getByTestId('extension-fallback')).toHaveTextContent('Badge is unavailable: it stopped with an error (boom). The rest of the page keeps working.')
    expect(screen.getByRole('button', { name: 'Rest of the page' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Retry Badge' }))
    expect(frame()).toBeInTheDocument()
  })

  it('falls back when the bridge never starts or stops answering heartbeats', async () => {
    setup({ readyTimeoutMs: 1000 })
    await act(async () => { vi.advanceTimersByTime(1100) })
    expect(screen.getByTestId('extension-fallback')).toHaveTextContent('it did not start in time')
  })

  it('falls back when the frame stops answering or navigates away from its document', async () => {
    const first = setup({ heartbeatMs: 500 })
    fromFrame({ protocol: PROTOCOL, type: 'ready' })
    await act(async () => { vi.advanceTimersByTime(1100) })
    expect(screen.getByTestId('extension-fallback')).toHaveTextContent('it stopped responding')
    first.unmount()
    setup()
    fireEvent.load(frame())
    fireEvent.load(frame())
    expect(screen.getByTestId('extension-fallback')).toHaveTextContent('navigated away')
  })

  it('shows a blocked version without loading a frame and has no axe violations', async () => {
    vi.useRealTimers()
    const { container } = render(<ExtensionHost label="Badge" source={null} data={{}} chains={[]} height={160} blocked="its version is revoked" onRun={vi.fn()} />)
    expect(screen.queryByTitle('Badge')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('its version is revoked')
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations).toEqual([])
  })
})
