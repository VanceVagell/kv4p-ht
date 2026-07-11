#!/usr/bin/env python3
"""Mock bielefeld-live ingest sink for testing the firmware uplink.

Implements the backend ingest contract (backend_plan.md §Ingest) on one port:
  POST /api/v1/ingest              -> 202 {"accepted":N,"rejected":0}
  GET  /ws/ingest  (WebSocket)     -> hello/hello_ack, burst/batch, ack

Every received burst is printed with its protocol tag and hex frame.

Usage:
  pip install aiohttp
  python3 mock_ingest.py [--port 8000] [--token STATIONKEY]

Point the firmware's uplink endpoint at http://<host>:8000 (HTTP POST) or
ws://<host>:8000 (WebSocket). With --token, requests must carry
"Authorization: Bearer <token>" (or a WS hello with the matching key).
"""
import argparse
import json

from aiohttp import WSMsgType, web

ARGS = None


def authed(request):
    if not ARGS.token:
        return True
    return request.headers.get("Authorization", "") == f"Bearer {ARGS.token}"


def show(transport, burst):
    print(f"[{transport}] proto={burst.get('protocol', 'g2')} "
          f"raw_hex={burst.get('raw_hex')} received_at={burst.get('received_at', '(server time)')}")


async def ingest(request):
    if not authed(request):
        return web.json_response({"error": "unauthorized"}, status=401)
    body = await request.json()
    bursts = body.get("bursts", [])
    for b in bursts:
        show("http", b)
    return web.json_response({"accepted": len(bursts), "rejected": 0}, status=202)


async def ws_ingest(request):
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    ok = authed(request)
    print(f"[ws] client connected (header auth: {ok})")
    n = 0
    async for msg in ws:
        if msg.type != WSMsgType.TEXT:
            continue
        try:
            d = json.loads(msg.data)
        except ValueError:
            await ws.send_json({"type": "error", "reason": "bad json"})
            continue
        t = d.get("type")
        if t == "hello":
            ok = not ARGS.token or d.get("station_key") == ARGS.token
            await ws.send_json({"type": "hello_ack", "station_id": "mock"})
        elif t == "burst":
            if not ok:
                await ws.send_json({"type": "error", "reason": "unauthorized"})
                continue
            n += 1
            show("ws", d)
            await ws.send_json({"type": "ack", "accepted": 1})
        elif t == "batch":
            if not ok:
                await ws.send_json({"type": "error", "reason": "unauthorized"})
                continue
            bursts = d.get("bursts", [])
            n += len(bursts)
            for b in bursts:
                show("ws", b)
            await ws.send_json({"type": "ack", "accepted": len(bursts)})
    print(f"[ws] closed after {n} bursts")
    return ws


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--token", default=None, help="require this bearer/station key")
    ARGS = ap.parse_args()
    app = web.Application()
    app.router.add_post("/api/v1/ingest", ingest)
    app.router.add_get("/ws/ingest", ws_ingest)
    print(f"mock ingest sink on 0.0.0.0:{ARGS.port} (token {'set' if ARGS.token else 'not required'})")
    web.run_app(app, port=ARGS.port, print=None)


if __name__ == "__main__":
    main()
