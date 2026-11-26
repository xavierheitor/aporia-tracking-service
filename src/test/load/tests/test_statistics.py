
import unittest

from metrics import (
    Latency,
    Statistics,
    percentile,
)


class StatisticsTest(unittest.TestCase):

    def test_percentiles(self):
        values = list(range(1, 101))

        self.assertEqual(percentile(values, 50), 50)
        self.assertEqual(percentile(values, 95), 95)
        self.assertEqual(percentile(values, 99), 99)

    def test_empty_latency(self):
        latency = Latency()

        self.assertEqual(latency.summary()["count"], 0)
        self.assertIsNone(latency.summary()["p95_ms"])

    def test_latency_conversion(self):
        latency = Latency()

        latency.record(0.010)
        latency.record(0.020)
        latency.record(0.030)

        result = latency.summary()

        self.assertEqual(result["count"], 3)
        self.assertAlmostEqual(result["mean_ms"], 20)
        self.assertAlmostEqual(result["p95_ms"], 30)

    def test_connection_peak(self):
        stats = Statistics()

        stats.connected()
        stats.connected()
        stats.disconnected()

        self.assertEqual(stats.connections_active, 1)
        self.assertEqual(stats.connections_peak, 2)

    def test_throughput(self):
        stats = Statistics()

        stats.packets_written = 100
        stats.packets_acknowledged = 50

        result = stats.summary(10)

        self.assertEqual(
            result["packets"]["written_per_second"],
            10,
        )

        self.assertEqual(
            result["packets"]["acknowledged_per_second"],
            5,
        )


if __name__ == "__main__":
    unittest.main()
