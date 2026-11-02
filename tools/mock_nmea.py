#!/usr/bin/env python3
"""
Mock NMEA 0183 TCP multiplexer for Derrotero (stdlib only).

Streams own-vessel sentences (GGA/RMC/VTG/HDT/DPT/MTW/MWV/MDA/RMB) and AIS
(!AIVDM type 1 + multipart type 5) at 1 Hz, with valid checksums.

Run:
    python tools/mock_nmea.py --port 10110

In the app: Ajustes -> NMEA 0183 -> TCP -> host = IP del PC (10.0.2.2 en emulador),
puerto 10110 -> Activar -> Guardar NMEA.
"""
import argparse
import math
import socketserver
import time

START_LAT, START_LON = 43.3000, -2.9500
SIX = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#$%&'()*+,-./0123456789:;<=>?"


def checksum(body):
    value = 0
    for char in body:
        value ^= ord(char)
    return value


def line(body):
    return f"{body}*{checksum(body[1:]):02X}"


def offset(lat, lon, bearing_deg, meters):
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


def coord(value, is_lat):
    hemi = ("N" if value >= 0 else "S") if is_lat else ("E" if value >= 0 else "W")
    absolute = abs(value)
    degrees = int(absolute)
    minutes = (absolute - degrees) * 60.0
    width = 2 if is_lat else 3
    return f"{degrees:0{width}d}{minutes:07.4f}", hemi


class Bits:
    def __init__(self, count):
        self.bits = [0] * count

    def uint(self, start, length, value):
        value = int(value) & ((1 << length) - 1)
        for i in range(length):
            self.bits[start + i] = (value >> (length - 1 - i)) & 1

    def sint(self, start, length, value):
        value = int(value)
        if value < 0:
            value = (1 << length) + value
        self.uint(start, length, value)

    def text(self, start, length, value):
        value = (value or "").upper()[: length // 6].ljust(length // 6, "@")
        for i, char in enumerate(value):
            index = SIX.find(char)
            self.uint(start + i * 6, 6, max(0, index))

    def payload(self):
        out = []
        for i in range(0, len(self.bits), 6):
            value = 0
            for bit in self.bits[i : i + 6]:
                value = (value << 1) | bit
            out.append(chr(value + 48 if value < 40 else value + 56))
        return "".join(out)


def ais_type1(mmsi, lat, lon, sog_kn, cog_deg, heading_deg):
    message = Bits(168)
    message.uint(0, 6, 1)
    message.uint(8, 30, mmsi)
    message.uint(38, 4, 0)
    message.sint(42, 8, 0)
    message.uint(50, 10, int(round(sog_kn / 0.1)))
    message.uint(60, 1, 1)
    message.sint(61, 28, round(lon * 600000))
    message.sint(89, 27, round(lat * 600000))
    message.uint(116, 12, int(round(cog_deg / 0.1)))
    message.uint(128, 9, int(round(heading_deg)))
    return line(f"!AIVDM,1,1,,A,{message.payload()},0")


def ais_type18(mmsi, lat, lon, sog_kn, cog_deg, heading_deg):
    message = Bits(168)
    message.uint(0, 6, 18)
    message.uint(8, 30, mmsi)
    message.uint(46, 10, int(round(sog_kn / 0.1)))
    message.uint(56, 1, 0)
    message.sint(57, 28, round(lon * 600000))
    message.sint(85, 27, round(lat * 600000))
    message.uint(112, 12, int(round(cog_deg / 0.1)))
    message.uint(124, 9, int(round(heading_deg)))
    return line(f"!AIVDM,1,1,,A,{message.payload()},0")


def ais_type24_parts(mmsi, name, callsign, length=120, beam=16):
    part_a = Bits(160)
    part_a.uint(0, 6, 24)
    part_a.uint(8, 30, mmsi)
    part_a.uint(38, 2, 0)
    part_a.text(40, 120, name)

    part_b = Bits(168)
    part_b.uint(0, 6, 24)
    part_b.uint(8, 30, mmsi)
    part_b.uint(38, 2, 1)
    part_b.uint(40, 8, 36)
    part_b.text(90, 42, callsign)
    part_b.uint(132, 9, int(length * 0.8))
    part_b.uint(141, 9, length - int(length * 0.8))
    part_b.uint(150, 6, beam // 2)
    part_b.uint(156, 6, beam - beam // 2)

    return [
        line(f"!AIVDM,1,1,,A,{part_a.payload()},0"),
        line(f"!AIVDM,1,1,,A,{part_b.payload()},0"),
    ]


def ais_type5_parts(mmsi, name, callsign, destination, ship_type=70, length=120, beam=16, draught=4.5):
    message = Bits(424)
    message.uint(0, 6, 5)
    message.uint(8, 30, mmsi)
    message.uint(40, 30, 0)
    message.text(70, 42, callsign)
    message.text(112, 120, name)
    message.uint(232, 8, ship_type)
    bow = int(length * 0.8)
    stern = length - bow
    message.uint(240, 9, bow)
    message.uint(249, 9, stern)
    message.uint(258, 6, beam // 2)
    message.uint(264, 6, beam - beam // 2)
    message.uint(294, 8, int(round(draught / 0.1)))
    message.text(302, 120, destination)
    payload = message.payload()
    split = 60
    return [
        line(f"!AIVDM,2,1,{mmsi % 10},A,{payload[:split]},0"),
        line(f"!AIVDM,2,2,{mmsi % 10},A,{payload[split:]},2"),
    ]


class Boat:
    def __init__(self, lat, lon, heading, sog_kn, aws=8.2, awa=38.0, depth=12.5):
        self.lat = lat
        self.lon = lon
        self.heading = heading
        self.sog_kn = sog_kn
        self.aws = aws
        self.awa = awa
        self.depth = depth
        self.t = 0.0

    def step(self, dt=1.0):
        self.t += dt
        self.heading = (self.heading + 0.35 * math.sin(self.t / 20.0)) % 360.0
        self.sog_kn = 5.4 + 0.5 * math.sin(self.t / 9.0)
        self.lat, self.lon = offset(
            self.lat, self.lon, self.heading, self.sog_kn * 0.514444 * dt
        )

    def sentences(self):
        lat, lat_h = coord(self.lat, True)
        lon, lon_h = coord(self.lon, False)
        cog = self.heading
        sog = self.sog_kn
        timestamp = time.strftime("%H%M%S", time.gmtime())
        date = time.strftime("%d%m%y", time.gmtime())
        aws = self.aws + 1.4 * math.sin(self.t / 7.0)
        awa = self.awa + 9.0 * math.sin(self.t / 15.0)
        depth = self.depth + 3.0 * math.sin(self.t / 18.0)
        out = [
            line(
                f"$GPGGA,{timestamp},{lat},{lat_h},{lon},{lon_h},1,08,0.9,"
                f"545.4,M,46.9,M,,"
            ),
            line(
                f"$GPRMC,{timestamp},A,{lat},{lat_h},{lon},{lon_h},"
                f"{sog:.1f},{cog:.1f},{date},0.0,W"
            ),
            line(f"$GPVTG,{cog:.1f},T,{cog:.1f},M,{sog:.1f},N,{sog*1.852:.1f},K"),
            line(f"$GPHDT,{self.heading:.1f},T"),
            line(f"$SDDPT,{depth:.1f},0.5"),
            line("$IIMTW,16.5,C"),
            line(f"$IIMWV,{awa:.0f},R,{aws:.1f},N,A"),
            line(
                "$IIMDA,29.85,I,1.013,B,21.5,C,16.5,C,60.0,40.0,12.0,C,"
                f"{cog:.1f},T,{(cog-5)%360:.1f},M,12.0,N,6.2,M"
            ),
            line(
                "$GPRMB,A,0.10,R,WP1,Marina,"
                f"{lat},{lat_h},{lon},{lon_h},2.50,045.0,5.0,A"
            ),
        ]
        return out


AIS = [
    {
        "mmsi": 211234567,
        "name": "NORDIC STAR",
        "callsign": "TEST123",
        "destination": "BILBAO",
        "class": "A",
        "lat": 43.3150,
        "lon": -2.9350,
        "heading": 210.0,
        "sog": 6.5,
    },
    {
        "mmsi": 211234568,
        "name": "SEA BREEZE",
        "callsign": "CALL99",
        "destination": "SANTANDER",
        "class": "B",
        "lat": 43.2850,
        "lon": -2.9750,
        "heading": 30.0,
        "sog": 4.2,
    },
    {
        "mmsi": 211234569,
        "name": "BLUE HORIZON",
        "callsign": "HORIZON",
        "destination": "BILBAO",
        "class": "A",
        "lat": 43.3100,
        "lon": -2.9700,
        "heading": 120.0,
        "sog": 5.8,
    },
]


def static_messages():
    parts = []
    for target in AIS:
        if target["class"] == "B":
            parts.extend(
                ais_type24_parts(target["mmsi"], target["name"], target["callsign"])
            )
        else:
            parts.extend(
                ais_type5_parts(
                    target["mmsi"],
                    target["name"],
                    target["callsign"],
                    target["destination"],
                )
            )
    return parts


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        boat = Boat(START_LAT, START_LON, 45.0, 5.4)
        static = static_messages()
        last_static = 0.0
        print(f"NMEA client connected: {self.client_address}")
        try:
            while True:
                now = time.time()
                lines = boat.sentences()
                for target in AIS:
                    builder = ais_type18 if target["class"] == "B" else ais_type1
                    lines.append(
                        builder(
                            target["mmsi"],
                            target["lat"],
                            target["lon"],
                            target["sog"],
                            target["heading"],
                            target["heading"],
                        )
                    )
                if now - last_static > 15.0:
                    lines.extend(static)
                    last_static = now
                for sentence in lines:
                    self.request.sendall((sentence + "\r\n").encode("ascii"))
                time.sleep(1.0)
                boat.step(1.0)
                for target in AIS:
                    target["lat"], target["lon"] = offset(
                        target["lat"],
                        target["lon"],
                        target["heading"],
                        target["sog"] * 0.514444,
                    )
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            print(f"NMEA client disconnected: {self.client_address}")


def main():
    parser = argparse.ArgumentParser(description="Mock NMEA 0183 TCP multiplexer")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=10110)
    args = parser.parse_args()

    server = socketserver.ThreadingTCPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"Mock NMEA 0183: tcp://{args.host}:{args.port}")
    print("  Frases: GGA, RMC, VTG, HDT, DPT, MTW, MWV, MDA, RMB")
    print("  AIS: 3 objetivos (tipo 1 + tipo 5 multipart)")
    print("  En la app: Ajustes -> NMEA 0183 -> TCP -> host del PC, puerto 10110")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
