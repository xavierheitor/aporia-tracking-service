
from datetime import datetime, timezone
import math
import re


def encode_position(
    identity: str,
    timestamp: datetime,
    latitude: float,
    longitude: float,
    speed: float = 45.5,
    ignition: bool = True,
) -> bytes:
    if not re.fullmatch(r"[0-9]{10}", identity):
        raise ValueError("Invalid Suntech identity")

    if timestamp.utcoffset() is None:
        raise ValueError("Timestamp must be timezone-aware")

    if not -90 <= latitude <= 90:
        raise ValueError("Invalid latitude")

    if not -180 <= longitude <= 180:
        raise ValueError("Invalid longitude")

    if not math.isfinite(speed) or speed < 0:
        raise ValueError("Invalid speed")

    timestamp = timestamp.astimezone(timezone.utc)

    frame = (
        f"STT;{identity};013831;"
        f"{timestamp:%Y%m%d};"
        f"{timestamp:%H:%M:%S};"
        f"{latitude:.6f};"
        f"{longitude:.6f};"
        f"{speed:.2f};"
        f"{int(ignition)}\r"
    )

    return frame.encode("ascii")
