"""Development-only SMTP sink. Keeps bounded messages in memory; never relays email."""
import asyncio
import json
import threading
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MESSAGES = []
LOCK = threading.Lock()


async def smtp(reader, writer):
    async def reply(text):
        writer.write((text + "\r\n").encode("ascii"))
        await writer.drain()

    recipients = []
    sender = ""
    try:
        await reply("220 orqix-capture ESMTP development sink")
        while True:
            raw = await asyncio.wait_for(reader.readline(), 30)
            if not raw:
                break
            if len(raw) > 4096:
                await reply("500 Line too long")
                break
            command = raw.decode("utf-8", "replace").strip()
            verb = command.split(" ", 1)[0].upper()
            if verb in {"EHLO", "HELO"}:
                await reply("250 orqix-capture")
            elif command.upper().startswith("MAIL FROM:"):
                sender = command[10:].strip()
                recipients = []
                await reply("250 Sender accepted")
            elif command.upper().startswith("RCPT TO:"):
                if len(recipients) >= 100:
                    await reply("452 Too many recipients")
                else:
                    recipients.append(command[8:].strip())
                    await reply("250 Recipient accepted")
            elif verb == "DATA":
                if not sender or not recipients:
                    await reply("503 MAIL and RCPT required")
                    continue
                await reply("354 End with a dot")
                body = bytearray()
                while True:
                    line = await asyncio.wait_for(reader.readline(), 30)
                    if not line:
                        return
                    if line == b".\r\n" or line == b".\n":
                        break
                    body.extend(line[1:] if line.startswith(b"..") else line)
                    if len(body) > 1_000_000:
                        await reply("552 Message too large")
                        return
                parsed = BytesParser(policy=policy.default).parsebytes(body)
                part = parsed.get_body(preferencelist=("plain",)) if parsed.is_multipart() else parsed
                message = {"id": str(parsed.get("Message-ID", "")), "from": sender,
                           "to": recipients[:], "subject": str(parsed.get("Subject", "")),
                           "body": part.get_content() if part else ""}
                with LOCK:
                    MESSAGES.append(message)
                    del MESSAGES[:-1000]
                await reply("250 Captured locally; no external delivery")
            elif verb == "RSET":
                sender = ""
                recipients = []
                await reply("250 Reset")
            elif verb == "NOOP":
                await reply("250 OK")
            elif verb == "QUIT":
                await reply("221 Bye")
                break
            else:
                await reply("502 Unsupported command")
    except (TimeoutError, ConnectionError, ValueError):
        pass
    finally:
        writer.close()
        await writer.wait_closed()


class Http(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/messages":
            with LOCK:
                body = json.dumps({"messages": MESSAGES[:]}).encode()
            kind = "application/json"
        elif self.path == "/health":
            body, kind = b'{"status":"ok"}', "application/json"
        elif self.path == "/":
            body = b'<!doctype html><html lang="en"><meta charset="utf-8"><title>Local mail capture</title><h1>Local mail capture</h1><p>Synthetic test messages only. No mail is relayed.</p><p>Open <a href="/messages">captured messages as JSON</a>.</p></html>'
            kind = "text/html;charset=utf-8"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


async def main():
    threading.Thread(target=ThreadingHTTPServer(("0.0.0.0", 1080), Http).serve_forever, daemon=True).start()
    server = await asyncio.start_server(smtp, "0.0.0.0", 1025, limit=1_010_000)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
