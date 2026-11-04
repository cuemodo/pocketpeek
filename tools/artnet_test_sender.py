#!/usr/bin/env python3
"""Send moving ArtDmx test patterns, to check the R1 monitor without a console.

  python3 tools/artnet_test_sender.py                      # broadcast, universes 0 and 1
  python3 tools/artnet_test_sender.py --ip 192.168.1.42 -u 0 3 --fps 30
  python3 tools/artnet_test_sender.py --ip 127.0.0.1       # emulator, after:
                                                           #   adb emu redir add udp:6454:6454

Patterns per universe (fixed by universe number so you can tell them apart):
  ramp on ch 1-64, a chase on ch 65-128, a slow sine on ch 129-512 (sparse), ch 512 at full.
"""
import argparse
import math
import socket
import time


def artdmx(universe: int, seq: int, data: bytes) -> bytes:
    if len(data) % 2:
        data += b"\x00"
    return (b"Art-Net\x00" + (0x5000).to_bytes(2, "little") + (14).to_bytes(2, "big")
            + bytes([seq & 0xFF, 0, universe & 0xFF, (universe >> 8) & 0x7F])
            + len(data).to_bytes(2, "big") + data)


def frame(universe: int, t: float) -> bytes:
    d = bytearray(512)
    for i in range(64):                                   # static ramp
        d[i] = round(i * 255 / 63)
    pos = int(t * 8 + universe * 7) % 64                  # chase with a tail
    for k in range(4):
        d[64 + (pos - k) % 64] = 255 >> k
    for i in range(128, 511, 3):                          # slow sine, every 3rd channel
        d[i] = int(127.5 + 127.5 * math.sin(t * 1.5 + i / 20 + universe))
    d[511] = 255
    return bytes(d)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="255.255.255.255")
    ap.add_argument("-u", "--universes", type=int, nargs="+", default=[0, 1])
    ap.add_argument("--fps", type=float, default=30)
    a = ap.parse_args()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    print(f"Sending universes {a.universes} to {a.ip}:6454 at {a.fps} fps (Ctrl-C to stop)")
    seq, t0 = 1, time.time()
    while True:
        t = time.time() - t0
        for u in a.universes:
            s.sendto(artdmx(u, seq, frame(u, t)), (a.ip, 6454))
        seq = seq % 255 + 1
        time.sleep(1 / a.fps)


if __name__ == "__main__":
    main()
