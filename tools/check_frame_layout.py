"""
Checks that the frames fake_drone.py builds land where the app will look for them.

This exists because a MAVLink payload can be exactly the right length and still have
every field at the wrong offset. The length is what the wire enforces and what the
checksum covers; the offsets are convention, and nothing in the frame itself confirms
them. So the only honest check is to read the bytes back using the app's own table --
BatteryStatusLayout.EXTENDED -- and see whether the values that come out are the ones
that went in.

    python tools/check_frame_layout.py
"""

import os
import struct
import sys

# Import the sender by path rather than by cwd, so this runs from the repository root,
# from tools/, or from anywhere else without the caller having to know.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fake_drone as fd  # noqa: E402


def read_battery_status_extended(payload):
    """
    Decode a BATTERY_STATUS payload using the offsets BatteryStatusLayout.EXTENDED declares.

    Mirrors MavlinkDecoders.batteryStatus: voltages and voltages_ext are concatenated, then
    zero entries are dropped as padding. Deliberately hard-coded rather than imported, so
    this fails if the two ever disagree rather than quietly reading its own constants back.
    """
    def uint16(offset):
        return struct.unpack_from("<H", payload, offset)[0]

    def int16(offset):
        return struct.unpack_from("<h", payload, offset)[0]

    def int8(offset):
        return struct.unpack_from("<b", payload, offset)[0]

    declared = [uint16(18 + 2 * index) for index in range(10)]
    overflow = [uint16(40 + 2 * index) for index in range(4)]

    return {
        "cells": [value for value in declared + overflow if value > 0],
        "temperature": int16(16),
        "current_centiamps": int16(38),
        "remaining_percent": int8(51),
    }


def main():
    percent = 78.0
    built_cells = fd.cell_millivolts(percent)
    payload = fd.battery_status_payload(built_cells, int(percent))
    decoded = read_battery_status_extended(payload)

    pack_millivolts = sum(built_cells)

    print(f"payload length           : {len(payload)}  (app reference: 54)")
    print(f"cells built              : {len(built_cells)}")
    print(f"cells the app will read  : {len(decoded['cells'])}")
    print(f"  {decoded['cells']}")
    print(f"weak cell (6th) is lower : {built_cells[5] < built_cells[0]}")
    print(f"temperature              : {decoded['temperature']}  (0 -> 'not reported', correct)")
    print(f"current_battery          : {decoded['current_centiamps']} cA "
          f"= {decoded['current_centiamps'] / 100} A")
    print(f"battery_remaining        : {decoded['remaining_percent']} %")
    print()
    print(f"SYS_STATUS voltage sent  : {pack_millivolts} mV = {pack_millivolts / 1000:.3f} V")
    print(f"sum of app-read cells    : {sum(decoded['cells'])} mV")
    print(f"delta                    : {sum(decoded['cells']) - pack_millivolts} mV "
          f"(tolerance 472 mV)")

    failures = []
    if len(payload) != 54:
        failures.append(f"payload is {len(payload)} bytes, expected 54")
    if len(decoded["cells"]) != len(built_cells):
        failures.append(f"app reads {len(decoded['cells'])} cells, {len(built_cells)} were built")
    if decoded["cells"] != built_cells:
        failures.append("cell values do not survive the round trip")
    if decoded["current_centiamps"] != int(fd.LOAD_AMPS * 100):
        failures.append("current_amperes landed at the wrong offset")
    if decoded["remaining_percent"] != int(percent):
        failures.append("battery_remaining landed at the wrong offset")
    if not all(2.0 <= cell / 1000 <= 5.0 for cell in decoded["cells"]):
        failures.append("a decoded cell is outside the plausible 2.0-5.0 V band")

    print()
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        return 1

    print("OK: every field the app reads holds the value that was sent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
