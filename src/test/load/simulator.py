
import argparse
import asyncio
import json
import math
import time

from datetime import datetime, timezone
from pathlib import Path

from database import (
    Device,
    provision_fleet,
    snapshot,
    cleanup,
)

from metrics import Statistics
from protocols import suntech, teltonika


HOST = "127.0.0.1"
PORT = 15500

CONNECT_TIMEOUT = 10
IO_TIMEOUT = 10
CLOSE_TIMEOUT = 3


class LoginError(Exception):
    pass


class AckError(Exception):
    pass


def coordinates(index: int, sequence: int):
    angle = index * 0.01 + sequence * 0.002

    latitude = -16.67 + math.sin(angle) * 0.05
    longitude = -49.25 + math.cos(angle) * 0.05

    return latitude, longitude


async def close_connection(writer):
    if writer is None:
        return

    writer.close()

    try:
        await asyncio.wait_for(
            writer.wait_closed(),
            timeout=CLOSE_TIMEOUT,
        )
    except (OSError, asyncio.TimeoutError):
        writer.transport.abort()


async def simulate_device(
    device: Device,
    index: int,
    start_at: float,
    end_at: float,
    interval: float,
    initial_delay: float,
    stats: Statistics,
):
    loop = asyncio.get_running_loop()

    writer = None
    connected = False
    sequence = 0

    await asyncio.sleep(
        max(0, start_at + initial_delay - loop.time())
    )

    if loop.time() >= end_at:
        return

    try:
        stats.connections_attempted += 1

        started = loop.time()

        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(HOST, PORT),
                timeout=CONNECT_TIMEOUT,
            )
        except (OSError, asyncio.TimeoutError):
            stats.connections_failed += 1
            raise

        stats.connection_latency.record(
            loop.time() - started
        )

        stats.connected()
        connected = True

        if device.protocol == "teltonika":
            started = loop.time()

            writer.write(
                teltonika.encode_login(device.identity)
            )

            await asyncio.wait_for(
                writer.drain(),
                timeout=IO_TIMEOUT,
            )

            response = await asyncio.wait_for(
                reader.readexactly(1),
                timeout=IO_TIMEOUT,
            )

            if response != teltonika.LOGIN_ACK:
                raise LoginError("Login rejected")

            stats.login_latency.record(
                loop.time() - started
            )

        next_send = start_at + initial_delay

        while loop.time() < end_at:
            await asyncio.sleep(
                max(0, next_send - loop.time())
            )

            if loop.time() >= end_at:
                break

            timestamp = datetime.now(timezone.utc)

            latitude, longitude = coordinates(
                index,
                sequence,
            )

            if device.protocol == "suntech":
                frame = suntech.encode_position(
                    device.identity,
                    timestamp,
                    latitude,
                    longitude,
                )
            else:
                frame = teltonika.encode_position(
                    timestamp,
                    latitude,
                    longitude,
                    codec=device.codec,
                )

            stats.packets_attempted += 1

            started = loop.time()

            try:
                writer.write(frame)

                await asyncio.wait_for(
                    writer.drain(),
                    timeout=IO_TIMEOUT,
                )

                stats.packets_written += 1

                stats.write_latency.record(
                    loop.time() - started
                )

                if device.protocol == "teltonika":
                    response = await asyncio.wait_for(
                        reader.readexactly(4),
                        timeout=IO_TIMEOUT,
                    )

                    if response != teltonika.POSITION_ACK:
                        raise AckError("Invalid AVL ACK")

                    stats.packets_acknowledged += 1

                    stats.ack_latency.record(
                        loop.time() - started
                    )

            except (
                OSError,
                asyncio.TimeoutError,
                asyncio.IncompleteReadError,
                AckError,
            ):
                stats.packets_failed += 1
                raise

            sequence += 1
            next_send += interval

            now = loop.time()

            if next_send < now:
                missed = math.floor(
                    (now - next_send) / interval
                ) + 1

                stats.missed_intervals += missed
                next_send += missed * interval

    except LoginError:
        stats.login_failures += 1

    except AckError:
        stats.ack_failures += 1

    except asyncio.TimeoutError:
        stats.timeouts += 1

    except (OSError, asyncio.IncompleteReadError):
        stats.disconnects += 1

    finally:
        await close_connection(writer)

        if connected:
            stats.disconnected()


async def progress(stats, start_at, end_at):
    loop = asyncio.get_running_loop()

    while loop.time() < end_at:
        await asyncio.sleep(5)

        elapsed = max(
            0.001,
            loop.time() - start_at,
        )

        print(
            f"elapsed={elapsed:.1f}s "
            f"active={stats.connections_active} "
            f"peak={stats.connections_peak} "
            f"written={stats.packets_written} "
            f"acked={stats.packets_acknowledged} "
            f"failed={stats.packets_failed} "
            f"pps={stats.packets_written / elapsed:.2f}",
            flush=True,
        )


async def run_simulation(fleet, args):
    stats = Statistics()
    loop = asyncio.get_running_loop()

    start_at = loop.time()
    end_at = start_at + args.duration

    tasks = []

    for index, device in enumerate(fleet.devices):
        delay = (
            args.ramp_up * index / len(fleet.devices)
        )

        tasks.append(
            asyncio.create_task(
                simulate_device(
                    device,
                    index,
                    start_at,
                    end_at,
                    args.interval,
                    delay,
                    stats,
                )
            )
        )

    reporter = asyncio.create_task(
        progress(stats, start_at, end_at)
    )

    try:
        await asyncio.gather(*tasks)

    finally:
        for task in tasks:
            if not task.done():
                task.cancel()

        await asyncio.gather(
            *tasks,
            return_exceptions=True,
        )

        reporter.cancel()

        await asyncio.gather(
            reporter,
            return_exceptions=True,
        )

    elapsed = loop.time() - start_at

    return stats.summary(elapsed)


def wait_for_processing(
    tenant_id,
    expected,
    timeout=60,
):
    deadline = time.monotonic() + timeout

    while True:
        result = snapshot(tenant_id)

        print(
            f"Persistence: {result}",
            flush=True,
        )

        if (
            result["raw_packets"] >= expected
            and result["positions"] >= expected
        ):
            return result

        if time.monotonic() >= deadline:
            return result

        time.sleep(2)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Aporia Tracking load simulator"
    )

    parser.add_argument(
        "--devices",
        type=int,
        required=True,
    )

    parser.add_argument(
        "--protocol",
        choices=["suntech", "teltonika", "mixed"],
        default="mixed",
    )

    parser.add_argument(
        "--interval",
        type=float,
        default=30,
    )

    parser.add_argument(
        "--duration",
        type=float,
        default=600,
    )

    parser.add_argument(
        "--ramp-up",
        type=float,
        default=30,
    )

    parser.add_argument(
        "--drain-timeout",
        type=float,
        default=60,
    )

    args = parser.parse_args()

    if not 1 <= args.devices <= 100000:
        parser.error("Invalid device count")

    if not math.isfinite(args.interval):
        parser.error("Invalid interval")

    if not 1 <= args.interval <= 120:
        parser.error("Interval must be between 1 and 120")

    if not math.isfinite(args.duration):
        parser.error("Invalid duration")

    if not math.isfinite(args.ramp_up):
        parser.error("Invalid ramp-up")

    if not 0 < args.ramp_up < args.duration:
        parser.error("Ramp-up must be less than duration")

    if args.duration - args.ramp_up < args.interval:
        parser.error("Insufficient measurement duration")

    if (
        not math.isfinite(args.drain_timeout)
        or args.drain_timeout <= 0
    ):
        parser.error("Invalid drain timeout")

    return args


def verify_gateway():
    import subprocess

    result = subprocess.run(
        [
            "docker",
            "compose",
            "--profile",
            "app",
            "exec",
            "-T",
            "gateway",
            "printenv",
            "TRACKING_DB_URL",
        ],
        capture_output=True,
        text=True,
        check=True,
    )

    expected = (
        "jdbc:postgresql://postgres-load:5432/"
        "aporia_tracking_test"
        "?currentSchema=tracking_load,public"
    )

    if result.stdout.strip() != expected:
        raise RuntimeError(
            "Gateway is not configured for benchmark"
        )

    port = subprocess.run(
        [
            "docker",
            "compose",
            "--profile",
            "app",
            "port",
            "gateway",
            "5500",
        ],
        capture_output=True,
        text=True,
        check=True,
    )

    if port.stdout.strip() != f"{HOST}:{PORT}":
        raise RuntimeError(
            "Unexpected gateway port"
        )


def main():
    args = parse_args()

    verify_gateway()

    fleet = provision_fleet(
        args.devices,
        args.protocol,
    )

    print(
        f"Tenant: {fleet.tenant_id}",
        flush=True,
    )

    print(
        f"Devices: {len(fleet.devices)}",
        flush=True,
    )

    try:
        result = asyncio.run(
            run_simulation(fleet, args)
        )

        written = result["packets"]["written"]

        persisted = wait_for_processing(
            fleet.tenant_id,
            written,
            args.drain_timeout,
        )

        result["tenant_id"] = str(fleet.tenant_id)
        result["scenario"] = vars(args)
        result["persistence"] = persisted

        result["reconciliation"] = {
            "raw_packets_delta": (
                persisted["raw_packets"] - written
            ),
            "positions_delta": (
                persisted["positions"] - written
            ),
        }

        directory = Path("runtime/load-testing")
        directory.mkdir(parents=True, exist_ok=True)

        report = directory / (
            f"{fleet.tenant_id}.json"
        )

        report.write_text(
            json.dumps(
                result,
                indent=2,
                allow_nan=False,
            ),
            encoding="utf-8",
        )

        print(json.dumps(result, indent=2))
        print(f"Report: {report}")

        print(
            "Tenant retained for verification:",
            fleet.tenant_id,
        )

    except KeyboardInterrupt:
        print("Benchmark interrupted")

        print(
            "Tenant retained for inspection:",
            fleet.tenant_id,
        )

        raise


if __name__ == "__main__":
    main()
