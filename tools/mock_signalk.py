#!/usr/bin/env python3
"""
Mock SignalK server for Derrotero (stdlib only, no pip installs).

Serves:
  GET  /signalk/v1/api/                          server info
  GET  /signalk/v1/api/vessels/self              full model (nested)
  GET  /signalk/v1/api/resources/{waypoints|routes}
  GET  /signalk/v1/api/resources/{type}/{id}
  PUT/POST/DELETE the same resources
  WS   /signalk/v1/stream?subscribe=all          1 Hz deltas (self + 3 AIS targets)

Run:
    python tools/mock_signalk.py --port 3000
Then in the app: Ajustes -> host = <this machine IP>, port = 3000, Probar.
"""
import argparse
import base64
import hashlib
import json
import math
import re
import select
import struct
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
LOCK = threading.Lock()
VERBOSE = False
TOKEN = ""
LEGACY_UNITS = False

START_LAT, START_LON = 43.3000, -2.9500
# Real Signal K servers identify the own vessel with a URN, NOT with "vessels.self".
SELF_ID = "vessels.urn:mrn:imo:mmsi:211234560"
STATE = {}
AIS = []
WAYPOINTS = {}
ROUTES = {}


# --------------------------------------------------------------------- helpers

def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def node(value):
    return {"value": value, "timestamp": now_iso()}


def offset(lat, lon, bearing_deg, meters):
    """Great-circle destination from (lat, lon) along bearing (deg) by meters."""
    r = 6371000.0
    d = meters / r
    br = math.radians(bearing_deg)
    lat1, lon1 = math.radians(lat), math.radians(lon)
    lat2 = math.asin(math.sin(lat1) * math.cos(d) + math.cos(lat1) * math.sin(d) * math.cos(br))
    lon2 = lon1 + math.atan2(
        math.sin(br) * math.sin(d) * math.cos(lat1),
        math.cos(d) - math.sin(lat1) * math.sin(lat2),
    )
    return math.degrees(lat2), (math.degrees(lon2) + 540.0) % 360.0 - 180.0


def angle(value_rad):
    """Publish radians normally; --legacy-units simulates plugins that send degrees."""
    return math.degrees(value_rad) if LEGACY_UNITS else value_rad


def temperature(kelvin):
    return kelvin - 273.15 if LEGACY_UNITS else kelvin


def init_state():
    STATE.update(t=0.0, lat=START_LAT, lon=START_LON, hdg=45.0, sog_kn=5.4)
    names = ["Nordic Star", "Sea Breeze", "Blue Horizon"]
    mmsi = ["211234567", "211234568", "211234569"]
    seed = [(43.3150, -2.9350, 210.0, 6.5), (43.2850, -2.9750, 30.0, 4.2), (43.3100, -2.9700, 120.0, 5.8)]
    for i, (lat, lon, hdg, sog) in enumerate(seed):
        AIS.append({
            "id": f"vessels.urn:mrn:imo:mmsi:{mmsi[i]}",
            "name": names[i],
            "mmsi": mmsi[i],
            "lat": lat,
            "lon": lon,
            "hdg": hdg,
            "sog": sog,
        })

    WAYPOINTS["mock-wp-1"] = {
        "name": "Marina (mock)",
        "description": "Waypoint de prueba",
        "position": {"latitude": 43.3230, "longitude": -2.9820},
    }
    WAYPOINTS["mock-wp-2"] = {
        "name": "Boya cardinal N",
        "position": {"latitude": 43.3160, "longitude": -2.9560},
    }
    WAYPOINTS["mock-wp-3"] = {
        "name": "Fondeadero",
        "position": {"latitude": 43.2980, "longitude": -2.9330},
    }
    # Real Signal K routes reference waypoints by href instead of embedding coordinates.
    ROUTES["mock-route-1"] = {
        "name": "Ruta de prueba",
        "description": "Generada por mock_signalk.py",
        "waypoints": [
            {"href": "/resources/waypoints/mock-wp-1"},
            {"href": "/resources/waypoints/mock-wp-2"},
            {"href": "/resources/waypoints/mock-wp-3"},
        ],
    }



def step(dt=1.0):
    with LOCK:
        STATE["t"] += dt
        t = STATE["t"]
        STATE["hdg"] = (STATE["hdg"] + 0.35 * math.sin(t / 20.0)) % 360.0
        STATE["sog_kn"] = 5.4 + 0.5 * math.sin(t / 9.0)
        mps = STATE["sog_kn"] * 0.514444
        STATE["lat"], STATE["lon"] = offset(STATE["lat"], STATE["lon"], STATE["hdg"], mps)
        for a in AIS:
            a["lat"], a["lon"] = offset(a["lat"], a["lon"], a["hdg"], a["sog"] * 0.514444)


def self_model():
    with LOCK:
        t = STATE["t"]
        aws = 8.2 + 1.4 * math.sin(t / 7.0)
        awa = math.radians(38.0 + 9.0 * math.sin(t / 15.0))
        stw = STATE["sog_kn"] * 0.514444 * 0.95
        vx = -aws * math.sin(awa)
        vy = -aws * math.cos(awa) + stw
        tws = math.hypot(vx, vy)
        twa = math.atan2(-vx, -vy)
        twd = (math.radians(STATE["hdg"]) + twa) % (2 * math.pi)
        return {
            "name": node("Demo Yacht"),
            "mmsi": node("211234560"),
            "design": {"mmsi": node("211234560")},
            "navigation": {
                "position": node({"latitude": STATE["lat"], "longitude": STATE["lon"]}),
                "speedOverGround": node(STATE["sog_kn"] * 0.514444),
                "courseOverGroundTrue": node(angle(math.radians(STATE["hdg"]))),
                "headingTrue": node(angle(math.radians(STATE["hdg"]))),
                "speedThroughWater": node(stw),
                "gnss": {"satellites": node(11)},
            },
            "environment": {
                "wind": {
                    "speedApparent": node(aws),
                    "angleApparent": node(angle(awa)),
                    "speedTrue": node(tws),
                    "angleTrueWater": node(angle(twa)),
                    "directionTrue": node(angle(twd)),
                },
                "depth": {"belowTransducer": node(12.5 + 3.0 * math.sin(t / 18.0))},
                "water": {"temperature": node(temperature(289.5))},
                "outside": {
                    "temperature": node(temperature(295.0)),
                    "pressure": node(1013.0 if LEGACY_UNITS else 101300.0),
                },
            },
        }


def deltas():
    """One self delta plus one delta per AIS target."""
    with LOCK:
        t = STATE["t"]
        aws = 8.2 + 1.4 * math.sin(t / 7.0)
        awa = math.radians(38.0 + 9.0 * math.sin(t / 15.0))
        stw = STATE["sog_kn"] * 0.514444 * 0.95
        vx = -aws * math.sin(awa)
        vy = -aws * math.cos(awa) + stw
        tws = math.hypot(vx, vy)
        twa = math.atan2(-vx, -vy)
        twd = (math.radians(STATE["hdg"]) + twa) % (2 * math.pi)

        out = [{
            "context": SELF_ID,
            "updates": [{
                "timestamp": now_iso(),
                "values": [
                    {"path": "navigation.position", "value": {"latitude": STATE["lat"], "longitude": STATE["lon"]}},
                    {"path": "navigation.speedOverGround", "value": STATE["sog_kn"] * 0.514444},
                    {"path": "navigation.courseOverGroundTrue", "value": angle(math.radians(STATE["hdg"]))},
                    {"path": "navigation.headingTrue", "value": angle(math.radians(STATE["hdg"]))},
                    {"path": "navigation.speedThroughWater", "value": stw},
                    {"path": "environment.wind.speedApparent", "value": aws},
                    {"path": "environment.wind.angleApparent", "value": angle(awa)},
                    {"path": "environment.wind.speedTrue", "value": tws},
                    {"path": "environment.wind.angleTrueWater", "value": angle(twa)},
                    {"path": "environment.wind.directionTrue", "value": angle(twd)},
                    {"path": "environment.depth.belowTransducer", "value": 12.5 + 3.0 * math.sin(t / 18.0)},
                    {"path": "environment.water.temperature", "value": temperature(289.5)},
                    {"path": "environment.outside.temperature", "value": temperature(295.0)},
                    {"path": "environment.outside.pressure", "value": 1013.0 if LEGACY_UNITS else 101300.0},
                    {"path": "navigation.gnss.satellites", "value": 11},
                ],
            }],
        }]

        for a in AIS:
            out.append({
                "context": a["id"],
                "updates": [{
                    "timestamp": now_iso(),
                    "values": [
                        {"path": "name", "value": a["name"]},
                        {"path": "mmsi", "value": a["mmsi"]},
                        {"path": "sensors.ais.class", "value": "A"},
                        {"path": "navigation.position", "value": {"latitude": a["lat"], "longitude": a["lon"]}},
                        {"path": "navigation.speedOverGround", "value": a["sog"] * 0.514444},
                        {"path": "navigation.courseOverGroundTrue", "value": angle(math.radians(a["hdg"]))},
                        {"path": "navigation.headingTrue", "value": angle(math.radians(a["hdg"]))},
                    ],
                }],
            })
        return out


# --------------------------------------------------------------- websocket I/O

def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def send_frame(conn, opcode, payload=b""):
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack(">H", n)
    else:
        header += bytes([127]) + struct.pack(">Q", n)
    conn.sendall(header + payload)


def send_text(conn, text):
    send_frame(conn, 0x1, text.encode("utf-8"))


def read_frame(conn):
    hdr = recv_exact(conn, 2)
    if not hdr:
        return None
    b0, b1 = hdr[0], hdr[1]
    opcode = b0 & 0x0F
    masked = b1 & 0x80
    length = b1 & 0x7F
    if length == 126:
        ext = recv_exact(conn, 2)
        if not ext:
            return None
        length = struct.unpack(">H", ext)[0]
    elif length == 127:
        ext = recv_exact(conn, 8)
        if not ext:
            return None
        length = struct.unpack(">Q", ext)[0]
    mask = recv_exact(conn, 4) if masked else None
    data = recv_exact(conn, length) if length else b""
    if data is None:
        return None
    if mask:
        data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
    return opcode, data


# ------------------------------------------------------------------- HTTP layer

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if VERBOSE:
            super().log_message(fmt, *args)

    def _json(self, obj, code=200, headers=None):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if not TOKEN:
            return True
        return self.headers.get("Authorization", "") == f"Bearer {TOKEN}"

    def _deny(self):
        self._json({"error": "unauthorized", "message": "falta el token Bearer"}, 401)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return {}

    # ------------------------------------------------------------------ GET

    def do_GET(self):
        if not self._authorized():
            self._deny()
            return
        path = urlparse(self.path).path
        if path.rstrip("/") == "/signalk/v1/stream":
            self._ws()
            return
        if path.rstrip("/") in ("/signalk/v1/api", ""):
            self._json({
                "server": {"id": "mock-signalk", "version": "1.0.0-mock"},
                "self": SELF_ID,
                "endpoints": {
                    "v1": {
                        "signalk-http": "/signalk/v1/api/",
                        "signalk-ws": "/signalk/v1/stream",
                    },
                },
            })
            return
        if path.rstrip("/") == "/signalk/v1/api/self":
            self._json(SELF_ID)
            return
        if path.rstrip("/") == "/signalk/v1/api/vessels/self":
            self._json(self_model())
            return
        m = re.match(r"^/signalk/v1/api/resources/([^/]+)$", path)
        if m:
            self._json(_collection(m.group(1)))
            return
        m = re.match(r"^/signalk/v1/api/resources/([^/]+)/([^/]+)$", path)
        if m:
            obj = _get_res(m.group(1), m.group(2))
            if obj is None:
                self._json({"error": "not found"}, 404)
            else:
                self._json(obj)
            return
        self._json({"error": "not found", "path": path}, 404)

    # ---------------------------------------------------------- resources

    def do_POST(self):
        if not self._authorized():
            self._deny()
            return
        path = urlparse(self.path).path
        m = re.match(r"^/signalk/v1/api/resources/([^/]+)$", path)
        if not m:
            self._json({"error": "not found"}, 404)
            return
        rid = str(uuid.uuid4())
        _put_res(m.group(1), rid, self._read_json())
        self._json({"ok": True}, 201, {"Location": f"/signalk/v1/api/resources/{m.group(1)}/{rid}"})

    def do_PUT(self):
        if not self._authorized():
            self._deny()
            return
        path = urlparse(self.path).path
        m = re.match(r"^/signalk/v1/api/resources/([^/]+)/([^/]+)$", path)
        if not m:
            self._json({"error": "not found"}, 404)
            return
        _put_res(m.group(1), m.group(2), self._read_json())
        self._json({"ok": True})

    def do_DELETE(self):
        if not self._authorized():
            self._deny()
            return
        path = urlparse(self.path).path
        m = re.match(r"^/signalk/v1/api/resources/([^/]+)/([^/]+)$", path)
        if not m:
            self._json({"error": "not found"}, 404)
            return
        _del_res(m.group(1), m.group(2))
        self._json({"ok": True})

    # ------------------------------------------------------------ websocket

    def _ws(self):
        if not self._authorized():
            self._deny()
            return
        query = parse_qs(urlparse(self.path).query)
        subscribe = (query.get("subscribe") or ["all"])[0]
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_error(400, "missing Sec-WebSocket-Key")
            return
        accept = base64.b64encode(
            hashlib.sha1((key + WS_GUID).encode("ascii")).digest(),
        ).decode("ascii")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True

        def messages():
            deltas_all = deltas()
            if subscribe == "self":
                return deltas_all[:1]
            if subscribe not in ("all", ""):
                wanted = {
                    c.strip() for c in subscribe.split(",")
                    if c.strip() in ("vessels.self", SELF_ID)
                }
                return [m for m in deltas_all if m.get("context") in wanted]
            return deltas_all

        conn = self.connection
        try:
            # Initial snapshot as deltas so a fresh client paints immediately.
            step(1.0)
            for msg in messages():
                send_text(conn, json.dumps(msg))

            last = time.time()
            while True:
                now = time.time()
                if now - last >= 1.0:
                    last = now
                    step(1.0)
                    for msg in messages():
                        send_text(conn, json.dumps(msg))
                readable, _, _ = select.select([conn], [], [], 0.1)
                if readable:
                    frame = read_frame(conn)
                    if frame is None:
                        break
                    opcode, data = frame
                    if opcode == 0x8:      # close
                        break
                    if opcode == 0x9:      # ping -> pong
                        send_frame(conn, 0xA, data)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


# --------------------------------------------------------------- resource store

def _store(kind):
    if kind == "waypoints":
        return WAYPOINTS
    if kind == "routes":
        return ROUTES
    return None


def _collection(kind):
    store = _store(kind)
    if store is None:
        return {}
    with LOCK:
        # Real servers include a version key next to the resources.
        return {"version": "1.7.0", **dict(store)}


def _get_res(kind, rid):
    store = _store(kind)
    if store is None:
        return None
    with LOCK:
        return store.get(rid)


def _put_res(kind, rid, obj):
    store = _store(kind)
    if store is None:
        return
    with LOCK:
        store[rid] = obj


def _del_res(kind, rid):
    store = _store(kind)
    if store is None:
        return
    with LOCK:
        store.pop(rid, None)


def main():
    global VERBOSE, TOKEN, LEGACY_UNITS
    parser = argparse.ArgumentParser(description="Mock SignalK server for Derrotero")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=3000)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--token",
        default="",
        help="exige Authorization: Bearer <token> (como un servidor SignalK con seguridad)",
    )
    parser.add_argument(
        "--legacy-units",
        action="store_true",
        help="publica ángulos en grados, temperatura en °C y presión en hPa (prueba de normalización)",
    )
    args = parser.parse_args()
    VERBOSE = args.verbose
    TOKEN = args.token
    LEGACY_UNITS = args.legacy_units

    init_state()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"Mock SignalK: http://{args.host}:{args.port}")
    print(f"  self : {SELF_ID}")
    print(f"  REST : /signalk/v1/api/vessels/self  ·  /signalk/v1/api/self")
    print(f"  WS   : ws://{args.host}:{args.port}/signalk/v1/stream?subscribe=self|all")
    print(f"  Rutas: waypoints referenciados por href (formato real de SignalK)")
    if TOKEN:
        print(f"  Auth : Bearer {TOKEN}")
    if LEGACY_UNITS:
        print("  Unidades legacy: grados / °C / hPa")
    print(f"  Ajustes en la app: host del PC, puerto {args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
