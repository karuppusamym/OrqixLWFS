import { createServer, type IncomingHttpHeaders, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'

export interface Received {
  method: string
  path: string
  query: string
  headers: IncomingHttpHeaders
  body: string
  json(): any
}

interface Rule {
  method: string
  suffix: string
  status: number
  body?: string
  headers: Record<string, string>
  always: boolean
}

/** A loopback HTTP server that answers from a script and records every request. */
export class ScriptedServer {
  readonly received: Received[] = []
  private readonly rules: Rule[] = []
  private server!: Server

  static async start(): Promise<ScriptedServer> {
    const scripted = new ScriptedServer()
    scripted.server = createServer((req, res) => {
      const chunks: Buffer[] = []
      req.on('data', chunk => chunks.push(chunk as Buffer))
      req.on('end', () => {
        const url = new URL(req.url ?? '/', 'http://localhost')
        const body = Buffer.concat(chunks).toString('utf8')
        scripted.received.push({ method: req.method ?? '', path: url.pathname, query: url.search.slice(1), headers: req.headers, body, json: () => JSON.parse(body) })
        const index = scripted.rules.findIndex(r => r.method === req.method && url.pathname.endsWith(r.suffix))
        if (index < 0) {
          res.writeHead(500, { 'Content-Type': 'application/problem+json' })
          res.end(JSON.stringify({ code: 'INTERNAL_ERROR', detail: `no scripted answer for ${url.pathname}` }))
          return
        }
        const rule = scripted.rules[index]!
        if (!rule.always) scripted.rules.splice(index, 1)
        const headers: Record<string, string> = { ...rule.headers }
        if (rule.body) headers['Content-Type'] = rule.status >= 400 ? 'application/problem+json' : 'application/json'
        res.writeHead(rule.status, headers)
        res.end(rule.body ?? '')
      })
    })
    await new Promise<void>(resolve => scripted.server.listen(0, '127.0.0.1', resolve))
    return scripted
  }

  get baseUrl(): string {
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}/api/v1`
  }

  once(method: string, suffix: string, status: number, body?: unknown, headers: Record<string, string> = {}): this {
    this.rules.push({ method, suffix, status, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body), headers, always: false })
    return this
  }

  always(method: string, suffix: string, status: number, body?: unknown): this {
    this.rules.push({ method, suffix, status, body: body === undefined ? undefined : JSON.stringify(body), headers: {}, always: true })
    return this
  }

  requests(method: string, suffix: string): Received[] {
    return this.received.filter(r => r.method === method && r.path.endsWith(suffix))
  }

  /** Waits up to `ms` for a matching request. */
  async waitFor(test: (r: Received) => boolean, ms = 10_000): Promise<Received> {
    const deadline = Date.now() + ms
    while (Date.now() < deadline) {
      const found = this.received.find(test)
      if (found) return found
      await new Promise(resolve => setTimeout(resolve, 10))
    }
    throw new Error(`no matching request within ${ms} ms`)
  }

  close(): Promise<void> {
    this.server.closeAllConnections()
    return new Promise(resolve => this.server.close(() => resolve()))
  }
}
