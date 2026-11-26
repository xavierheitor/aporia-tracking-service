
from datetime import datetime
import re
import struct


CODEC_8 = 0x08
CODEC_8E = 0x8E

LOGIN_ACK = b"\x01"
POSITION_ACK = struct.pack(">I", 1)


def crc16_ibm(data: bytes) -> int:
    crc = 0

    for byte in data:
        crc ^= byte

        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1

    return crc & 0xFFFF


def encode_login(imei: str) -> bytes:
    if not re.fullmatch(r"[0-9]{15}", imei):
        raise ValueError("Invalid Teltonika IMEI")

    identity = imei.encode("ascii")

    return struct.pack(">H", len(identity)) + identity


def encode_position(
    timestamp: datetime,
    latitude: float,
    longitude: float,
    speed: int = 45,
    ignition: bool = True,
    codec: int = CODEC_8,
) -> bytes:
    if timestamp.utcoffset() is None:
        raise ValueError("Timestamp must be timezone-aware")

    if codec not in (CODEC_8, CODEC_8E):
        raise ValueError("Unsupported codec")

    if not -90 <= latitude <= 90:
        raise ValueError("Invalid latitude")

    if not -180 <= longitude <= 180:
        raise ValueError("Invalid longitude")

    if not 0 <= speed <= 65535:
        raise ValueError("Invalid speed")

    timestamp_ms = int(timestamp.timestamp() * 1000)

    gps = struct.pack(
        ">iiHHBH",
        round(longitude * 10_000_000),
        round(latitude * 10_000_000),
        750,  # Altitude
        90,   # Heading
        8,    # Satellites
        speed,
    )

    record = (
        struct.pack(">QB", timestamp_ms, 0)
        + gps
    )

    if codec == CODEC_8:
        io = struct.pack(
            ">BBBBBBBB",
            0,              # Event IO ID
            1,              # Total IO elements
            1,              # One-byte IO count
            239,            # Ignition IO ID
            int(ignition),
            0,              # Two-byte IO count
            0,              # Four-byte IO count
            0,              # Eight-byte IO count
        )
    else:
        io = struct.pack(
            ">HHHHBHHHH",
            0,              # Event IO ID
            1,              # Total IO elements
            1,              # One-byte IO count
            239,            # Ignition IO ID
            int(ignition),
            0,              # Two-byte IO count
            0,              # Four-byte IO count
            0,              # Eight-byte IO count
            0,              # Variable-size IO count
        )

    payload = (
        bytes([codec, 1])
        + record
        + io
        + bytes([1])
    )

    return (
        struct.pack(">II", 0, len(payload))
        + payload
        + struct.pack(">I", crc16_ibm(payload))
    )
