#!/usr/bin/env python3
"""
Regression test for the SignalK behaviours that the app originally mishandled.

It starts tools/mock_signalk.py in-process and checks:
  * the self vessel is identified by a URN (not "vessels.self");
  * GET /signalk/v1/api/self resolves it;
  * the delta stream with subscribe=self only carries the own vessel;
  * subscribe=all carries AIS targets too;
  * resource collections include a version key and routes reference waypoints by href;
  * an access token is enforced when configured.

Run:  python tools/selftest_signalk.py
"""
import base64
import importlib.util
import json
import os
import socket
import struct
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))


def load_mock():
    path = os.path.join(HERE, "mock_signalk.py")
    spec = importlib.util.spec_from_file_location("mock_signalk", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise AssertionError("socket cerrado antes de tiempo")
        data += chunk
    return data


def recv_until(sock, marker):
    data = b""
    while marker not in data:
        data += sock.recv(4096)
    return data


def read_frame(sock):
    b0, b1 = recv_exact(sock, 2)
    opcode = b0 & 0x0F
    length = b1 & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, 8))[0]
    payload = recv_exact(sock, length) if length else b""
    return opcode, payload


def ws_connect(port, subscribe, token=""):
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    auth = f"Authorization: Bearer {token}\r\n" if token else ""
    request = (
        f"GET /signalk/v1/stream?subscribe={subscribe} HTTP/1.1\r\n"
        f"Host: 127.0.0.1:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"{auth}"
        "\r\n"
    )
    sock.sendall(request.encode("ascii"))
    header = recv_until(sock, b"\r\n\r\n").decode("latin1")
    if " 101 " not in header.split("\r\n")[0]:
        sock.close()
        raise AssertionError(f"handshake fallido: {header.splitlines()[0]}")
    return sock


def main():
    mock = load_mock()
    mock.init_state()
    server = ThreadingHTTPServer(("127.0.0.1", 0), mock.Handler)
    server.daemon_threads = True
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}"

    def get(path, token=""):
        req = urllib.request.Request(base + path)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))

    failures = []

    def check(name, condition, detail=""):
        if condition:
            print(f"  OK   {name}")
        else:
            print(f"  FAIL {name} {detail}")
            failures.append(name)

    print("REST")
    self_id = get("/signalk/v1/api/self")
    check("self es un URN", isinstance(self_id, str) and self_id.startswith("vessels.urn:"), self_id)
    root = get("/signalk/v1/api/")
    check("root expone self", root.get("self") == self_id)
    model = get("/signalk/v1/api/vessels/self")
    check("modelo incluye mmsi", model.get("mmsi", {}).get("value") == "211234560")

    routes = get("/signalk/v1/api/resources/routes")
    check("colección con version", routes.get("version") == "1.7.0")
    route = routes["mock-route-1"]
    check("ruta referencia waypoints por href", "href" in route["waypoints"][0], route["waypoints"][0])

    print("WebSocket")
    sock = ws_connect(port, "self")
    _, payload = read_frame(sock)
    delta = json.loads(payload.decode("utf-8"))
    check("subscribe=self usa el URN del barco", delta.get("context") == self_id, delta.get("context"))
    check(
        "subscribe=self trae posición",
        any(v["path"] == "navigation.position" for v in delta["updates"][0]["values"]),
    )
    sock.close()

    sock = ws_connect(port, "all")
    contexts = set()
    for _ in range(4):
        _, payload = read_frame(sock)
        contexts.add(json.loads(payload.decode("utf-8")).get("context"))
    sock.close()
    check("subscribe=all incluye el barco", self_id in contexts, contexts)
    check("subscribe=all incluye AIS", len(contexts) >= 4, contexts)

    print("Auth")
    mock.TOKEN = "s3cret"
    try:
        urllib.request.urlopen(base + "/signalk/v1/api/", timeout=5)
        denied = False
    except urllib.error.HTTPError as exc:
        denied = exc.code == 401
    check("sin token da 401", denied)
    check("con token funciona", get("/signalk/v1/api/", token="s3cret").get("self") == self_id)
    mock.TOKEN = ""

    server.shutdown()
    if failures:
        print(f"\n{len(failures)} comprobaciones fallidas")
        raise SystemExit(1)
    print("\nTodo correcto")


if __name__ == "__main__":
    main()
