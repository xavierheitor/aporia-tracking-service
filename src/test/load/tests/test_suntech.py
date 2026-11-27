
"""Testes do encoder de quadros Suntech STT."""

import unittest
from datetime import datetime, timezone, timedelta

from protocols.suntech import encode_position


class SuntechTest(unittest.TestCase):
    """Valida formato de referência, fuso e rejeição de entradas inválidas."""

    def test_reference_frame(self):
        """O quadro gerado deve casar byte a byte com o formato STT esperado."""
        timestamp = datetime(
            2026, 11, 19, 13, 30, 0,
            tzinfo=timezone.utc,
        )

        frame = encode_position(
            "1234567890",
            timestamp,
            -16.67,
            -49.25,
        )

        self.assertEqual(
            frame,
            b"STT;1234567890;013831;"
            b"20261119;13:30:00;"
            b"-16.670000;-49.250000;45.50;1\r",
        )

    def test_timezone_conversion(self):
        """Um horário com offset -03:00 é normalizado para UTC no quadro."""
        timestamp = datetime(
            2026, 11, 19, 10, 30,
            tzinfo=timezone(timedelta(hours=-3)),
        )

        frame = encode_position(
            "1234567890", timestamp, -16.67, -49.25
        )

        self.assertIn(b"13:30:00", frame)

    def test_invalid_identity(self):
        """Identidade fora do padrão de 10 dígitos é rejeitada."""
        with self.assertRaises(ValueError):
            encode_position(
                "123",
                datetime.now(timezone.utc),
                -16.67,
                -49.25,
            )

    def test_invalid_coordinates(self):
        """Latitude fora de -90..90 é rejeitada antes do envio."""
        with self.assertRaises(ValueError):
            encode_position(
                "1234567890",
                datetime.now(timezone.utc),
                -100,
                -49.25,
            )

    def test_naive_timestamp(self):
        """Datetime sem fuso não é aceito, pois o instante ficaria ambíguo."""
        with self.assertRaises(ValueError):
            encode_position(
                "1234567890",
                datetime.now(),
                -16.67,
                -49.25,
            )


if __name__ == "__main__":
    unittest.main()
