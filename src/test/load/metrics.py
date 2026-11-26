
import math
from dataclasses import dataclass, field
from typing import List, Optional


def percentile(
    ordered: List[float],
    percentage: float,
) -> Optional[float]:
    if not 0 <= percentage <= 100:
        raise ValueError("Invalid percentile")

    if not ordered:
        return None

    index = max(
        0,
        math.ceil(
            len(ordered) * percentage / 100
        ) - 1,
    )

    return ordered[index]


@dataclass
class Latency:
    samples: List[float] = field(default_factory=list)

    def record(self, seconds: float) -> None:
        if not math.isfinite(seconds) or seconds < 0:
            raise ValueError("Invalid latency")

        self.samples.append(seconds * 1000)

    def summary(self) -> dict:
        if not self.samples:
            return {
                "count": 0,
                "min_ms": None,
                "mean_ms": None,
                "p50_ms": None,
                "p95_ms": None,
                "p99_ms": None,
                "max_ms": None,
            }

        ordered = sorted(self.samples)

        return {
            "count": len(ordered),
            "min_ms": ordered[0],
            "mean_ms": sum(ordered) / len(ordered),
            "p50_ms": percentile(ordered, 50),
            "p95_ms": percentile(ordered, 95),
            "p99_ms": percentile(ordered, 99),
            "max_ms": ordered[-1],
        }


@dataclass
class Statistics:
    connections_attempted: int = 0
    connections_established: int = 0
    connections_failed: int = 0
    connections_active: int = 0
    connections_peak: int = 0

    packets_attempted: int = 0
    packets_written: int = 0
    packets_acknowledged: int = 0
    packets_failed: int = 0

    login_failures: int = 0
    ack_failures: int = 0
    timeouts: int = 0
    disconnects: int = 0
    reconnects: int = 0
    missed_intervals: int = 0

    connection_latency: Latency = field(
        default_factory=Latency
    )

    login_latency: Latency = field(
        default_factory=Latency
    )

    write_latency: Latency = field(
        default_factory=Latency
    )

    ack_latency: Latency = field(
        default_factory=Latency
    )

    def connected(self) -> None:
        self.connections_established += 1
        self.connections_active += 1

        self.connections_peak = max(
            self.connections_peak,
            self.connections_active,
        )

    def disconnected(self) -> None:
        if self.connections_active <= 0:
            raise RuntimeError(
                "Connection counter underflow"
            )

        self.connections_active -= 1

    def summary(self, elapsed: float) -> dict:
        if elapsed <= 0:
            raise ValueError("Invalid elapsed time")

        return {
            "elapsed_seconds": elapsed,
            "connections": {
                "attempted": self.connections_attempted,
                "established": self.connections_established,
                "failed": self.connections_failed,
                "active": self.connections_active,
                "peak": self.connections_peak,
                "disconnects": self.disconnects,
                "reconnects": self.reconnects,
            },
            "packets": {
                "attempted": self.packets_attempted,
                "written": self.packets_written,
                "acknowledged": self.packets_acknowledged,
                "failed": self.packets_failed,
                "written_per_second": (
                    self.packets_written / elapsed
                ),
                "acknowledged_per_second": (
                    self.packets_acknowledged / elapsed
                ),
                "missed_intervals": self.missed_intervals,
            },
            "errors": {
                "login": self.login_failures,
                "ack": self.ack_failures,
                "timeouts": self.timeouts,
            },
            "latency": {
                "connection": self.connection_latency.summary(),
                "login": self.login_latency.summary(),
                "write": self.write_latency.summary(),
                "ack": self.ack_latency.summary(),
            },
        }
