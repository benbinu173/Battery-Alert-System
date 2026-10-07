#!/usr/bin/env python3
"""
A fake agricultural drone: real, checksummed MAVLink v2 frames over UDP.

Its purpose is to prove the app's UDP path end to end without an aircraft, an
autopilot or SITL. It sends the same five message types an ArduPilot emits, with
the CRC extras the app's own MavlinkMessageSpec ships, so every frame is one the
app verifies and decodes rather than rejects.

Standard library only -- no pip install.

    python fake_drone.py [host] [port] [start_percent]

    host           default 127.0.0.1
    port           default 14550
    start_percent  default 78 -- the pack's state of charge when it starts

It drains the pack at DRAIN_PERCENT_PER_SECOND, so the dashboard's discharge
rate, flight-time estimate and RTL requirement are all computed from values
that are genuinely changing rather than from a frozen snapshot. Run it against a
pack starting near 20% to watch the alert engine and the spray interlock fire on
the way down.

WHY 2 Hz: the app's link watchdog calls two seconds of silence a link error, so
anything slower than 1 Hz would make a working link look broken.
"""

import socket
import struct
import sys
import time

STX_V2 = 0xFD
SYSTEM_ID = 1
COMPONENT_ID = 1

RATE_HZ = 2.0
DRAIN_PERCENT_PER_SECOND = 0.5

# 12S pack. The weak cell is the sixth, which is where the simulator in the app
# puts its own -- a real imbalance usually has one cell leading or lagging.
CELL_COUNT = 12
WEAK_CELL_INDEX = 5
WEAK_CELL_MILLIVOLTS = 20     # raise to ~120 to trip the Cell Fault alert
LOAD_AMPS = 12.0
INTERNAL_RESISTANCE_OHM_PER_CELL = 0.004

# (state of charge %, resting volts per cell), descending. Same shape as the
# curve in the app's simulator, so the numbers a reviewer sees here and there
# are recognisably the same kind of pack.
SOC_CURVE = [
    (100.0, 4.20),
    (80.0, 4.00),
    (50.0, 3.78),
    (20.0, 3.58),
    (0.0, 3.30),
]

# Per-message checksum seed. From MAVLINK_MSG_ID_<NAME>_CRC in the common
# dialect -- read off MavlinkMessageSpec.kt, which is the table this app
# actually validates against.
CRC_EXTRA = {
    0: 50,      # HEARTBEAT
    1: 124,     # SYS_STATUS
    33: 104,    # GLOBAL_POSITION_INT
    74: 20,     # VFR_HUD
    147: 154,   # BATTERY_STATUS
}

HOME_LATITUDE = 12.9716
HOME_LONGITUDE = 77.5946


# --- MAVLink framing -------------------------------------------------------

def _crc_accumulate(byte, crc):
    """The MAVLink CRC-16/MCRF4XX step, exactly as the C library does it."""
    tmp = (byte ^ (crc & 0xFF)) & 0xFF
    tmp = (tmp ^ (tmp << 4)) & 0xFF
    return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF


def _checksum(data, crc_extra):
    crc = 0xFFFF
    for byte in data:
        crc = _crc_accumulate(byte, crc)
    return _crc_accumulate(crc_extra, crc)


def frame(message_id, payload, sequence):
    """
    Wrap a payload in a MAVLink v2 frame.

    The checksum covers the header from the length byte onwards plus the payload
    -- the magic byte is excluded -- and is then seeded with the message's CRC
    extra. That is the same range the app's parser checks, which is why a frame
    built here is accepted there.
    """
    header = bytes([
        len(payload),
        0,                          # incompat_flags: unsigned
        0,                          # compat_flags
        sequence & 0xFF,
        SYSTEM_ID,
        COMPONENT_ID,
        message_id & 0xFF,
        (message_id >> 8) & 0xFF,
        (message_id >> 16) & 0xFF,
    ])
    crc = _checksum(header + payload, CRC_EXTRA[message_id])
    return bytes([STX_V2]) + header + payload + struct.pack("<H", crc)


# --- Payloads --------------------------------------------------------------

def heartbeat_payload():
    return struct.pack(
        "<IBBBBB",
        0,          # custom_mode
        2,          # type: MAV_TYPE_QUADROTOR
        3,          # autopilot: MAV_AUTOPILOT_ARDUPILOTMEGA
        0x80,       # base_mode: armed
        4,          # system_status: MAV_STATE_ACTIVE
        3,          # mavlink_version
    )


def sys_status_payload(millivolts, centiamps, remaining_percent):
    return struct.pack(
        "<IIIHHhHHHHHHb",
        0, 0, 0,                    # onboard_control_sensors present/enabled/health
        0,                          # load
        millivolts,                 # voltage_battery, millivolts
        centiamps,                  # current_battery, centiamps
        0, 0,                       # drop_rate_comm, errors_comm
        0, 0, 0, 0,                 # errors_count1..4
        remaining_percent,          # battery_remaining, percent
    )


def global_position_payload(latitude_deg, longitude_deg, altitude_m):
    return struct.pack(
        "<IiiiihhhH",
        0,                                      # time_boot_ms
        int(latitude_deg * 1e7),
        int(longitude_deg * 1e7),
        int(altitude_m * 1000),                 # alt, mm above mean sea level
        int(altitude_m * 1000),                 # relative_alt, mm
        0, 0, 0,                                # vx, vy, vz
        0,                                      # hdg, centidegrees (0 = unknown)
    )


def vfr_hud_payload(ground_speed_mps, altitude_m):
    return struct.pack(
        "<ffffhH",
        ground_speed_mps,           # airspeed
        ground_speed_mps,           # groundspeed
        altitude_m,                 # alt
        0.0,                        # climb
        0,                          # heading
        60,                         # throttle, percent
    )


def battery_status_payload(cell_millivolts, remaining_percent):
    """
    The current `common` BATTERY_STATUS definition, in wire order.

    MAVLink orders payload fields by size, largest first, and appends extension
    fields in declaration order -- so the extensions are *not* simply tacked on
    the end. `time_remaining` and `fault_bitmask` are 32-bit and sort up into the
    leading block, which pushes the uint16 and uint8 blocks below them along.
    Writing the fields in the order the specification declares them, or appending
    the extensions, produces a payload of exactly the right length with every
    field after `energy_consumed` at the wrong offset.

    That is worth spelling out because it is a silent failure: the frame passes
    its checksum, the link reads healthy, and the app reports numbers read from
    the wrong bytes. `BatteryStatusLayout` in the app exists for precisely this,
    and it withholds cell data it cannot corroborate rather than showing it.

    Offsets below match `BatteryStatusLayout.EXTENDED`:

        0   current_consumed  int32
        4   energy_consumed   int32
        8   time_remaining    int32   (extension)
        12  fault_bitmask     uint32  (extension)
        16  temperature       int16
        18  voltages[10]      uint16
        38  current_battery   int16
        40  voltages_ext[4]   uint16  (extension)
        48  id                uint8
        49  battery_function  uint8
        50  type              uint8
        51  battery_remaining int8
        52  charge_state      uint8   (extension)
        53  mode              uint8   (extension)

    Cells past the tenth go in `voltages_ext`. That is where a 12S pack's last
    two cells actually live -- a builder that dropped them would make a ten-cell
    reading look complete.
    """
    declared = list(cell_millivolts[:10]) + [0] * (10 - len(cell_millivolts[:10]))
    overflow = list(cell_millivolts[10:14]) + [0] * (4 - len(cell_millivolts[10:14]))

    return struct.pack(
        "<iiiIh10Hh4HBBBbBB",
        -1,                         # current_consumed, mAh (unknown)
        -1,                         # energy_consumed, hJ (unknown)
        -1,                         # time_remaining, s (unknown)
        0,                          # fault_bitmask
        0,                          # temperature, cdegC (0 = unknown)
        *declared,                  # voltages[10]
        int(LOAD_AMPS * 100),       # current_battery, centiamps
        *overflow,                  # voltages_ext[4]
        0,                          # id
        0,                          # battery_function
        1,                          # type: MAV_BATTERY_TYPE_LIPO
        remaining_percent,          # battery_remaining, percent
        0,                          # charge_state
        0,                          # mode
    )


# --- Physics ---------------------------------------------------------------

def resting_volts_per_cell(percent):
    if percent >= SOC_CURVE[0][0]:
        return SOC_CURVE[0][1]
    if percent <= SOC_CURVE[-1][0]:
        return SOC_CURVE[-1][1]
    for (high_soc, high_v), (low_soc, low_v) in zip(SOC_CURVE, SOC_CURVE[1:]):
        if low_soc <= percent <= high_soc:
            span = high_soc - low_soc
            ratio = 0.0 if span == 0 else (percent - low_soc) / span
            return low_v + ratio * (high_v - low_v)
    return SOC_CURVE[-1][1]


def cell_millivolts(percent):
    """
    A loaded voltage per cell: open-circuit voltage minus I x Ri sag.

    The pack's reported voltage is the sum of these, so the app's corroboration
    between SYS_STATUS and BATTERY_STATUS agrees. A builder that reported a
    plausible pack voltage next to unrelated cells would look like a module in
    the wrong dialect, and the app would withhold the cells rather than trust
    them.
    """
    resting = resting_volts_per_cell(percent)
    sag = LOAD_AMPS * INTERNAL_RESISTANCE_OHM_PER_CELL
    base = resting - sag

    return [
        int(round((base * 1000) - WEAK_CELL_MILLIVOLTS))
        if index == WEAK_CELL_INDEX
        else int(round(base * 1000))
        for index in range(CELL_COUNT)
    ]


# --- Main ------------------------------------------------------------------

def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 14550
    percent = float(sys.argv[3]) if len(sys.argv) > 3 else 78.0

    target = (host, port)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    interval = 1.0 / RATE_HZ
    sequence = 0
    ticks = 0

    print(f"sending MAVLink v2 to {host}:{port} at {RATE_HZ:g} Hz, pack at {percent:.0f}%")
    print("Ctrl-C to stop.\n")

    try:
        while True:
            cells = cell_millivolts(percent)
            pack_millivolts = sum(cells)

            for message_id, payload in (
                (0, heartbeat_payload()),
                (1, sys_status_payload(pack_millivolts, int(LOAD_AMPS * 100), int(percent))),
                (147, battery_status_payload(cells, int(percent))),
                (33, global_position_payload(HOME_LATITUDE + 0.0018, HOME_LONGITUDE, 18.0)),
                (74, vfr_hud_payload(9.5, 18.0)),
            ):
                sock.sendto(frame(message_id, payload, sequence), target)
                sequence = (sequence + 1) & 0xFF

            ticks += 1
            if ticks % int(RATE_HZ) == 0:
                print(f"  {percent:5.1f}%   {pack_millivolts / 1000.0:6.2f} V   "
                      f"{LOAD_AMPS:4.1f} A   {len(cells)} cells")

            percent = max(0.0, percent - DRAIN_PERCENT_PER_SECOND * interval)
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\nstopped.")
    finally:
        sock.close()


if __name__ == "__main__":
    main()
