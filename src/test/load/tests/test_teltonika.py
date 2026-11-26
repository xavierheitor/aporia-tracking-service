
import struct
import unittest
from datetime import datetime, timezone

from protocols.teltonika import (
    CODEC_8,
    CODEC_8E,
    crc16_ibm,
    encode_login,
    encode_position,
)


class TeltonikaTest(unittest.TestCase):

    def test_crc_standard_vector(self):
        self.assertEqual(
            crc16_ibm(b"123456789"),
            0xBB3D,
        )

    def test_crc_reference_frames(self):
        references = [
            "000000000000003608010000016B40D8EA30010000000000000000000000000000000105021503010101425E0F01F10000601A014E0000000000000000010000C7CF",
            "000000000000004A8E010000016B412CEE000100000000000000000000000000000000010005000100010100010011001D00010010015E2C880002000B000000003544C87A000E000000001DD7E06A00000100002994",
        ]

        for reference in references:
            with self.subTest(reference=reference):
                wire = bytes.fromhex(reference)
                payload = wire[8:-4]

                self.assertEqual(
                    len(payload),
                    struct.unpack(">I", wire[4:8])[0],
                )

                self.assertEqual(
                    crc16_ibm(payload),
                    struct.unpack(">I", wire[-4:])[0],
                )

    def test_login(self):
        self.assertEqual(
            encode_login("356307042441013"),
            b"\x00\x0f356307042441013",
        )

    def test_invalid_imei(self):
        with self.assertRaises(ValueError):
            encode_login("123")

    def test_codec_8(self):
        self.verify_position(CODEC_8, 35)

    def test_codec_8e(self):
        self.verify_position(CODEC_8E, 44)

    def verify_position(self, codec, expected_size):
        timestamp = datetime(
            2026, 11, 26, 12, 0,
            tzinfo=timezone.utc,
        )

        wire = encode_position(
            timestamp,
            -22.8,
            -47.0,
            codec=codec,
        )

        preamble, size = struct.unpack(">II", wire[:8])
        payload = wire[8:-4]
        crc = struct.unpack(">I", wire[-4:])[0]

        self.assertEqual(preamble, 0)
        self.assertEqual(size, expected_size)
        self.assertEqual(size, len(payload))
        self.assertEqual(crc, crc16_ibm(payload))

        self.assertEqual(payload[0], codec)
        self.assertEqual(payload[1], 1)
        self.assertEqual(payload[-1], 1)

        timestamp_ms = struct.unpack(
            ">Q", payload[2:10]
        )[0]

        longitude, latitude = struct.unpack(
            ">ii", payload[11:19]
        )

        self.assertEqual(
            timestamp_ms,
            int(timestamp.timestamp() * 1000),
        )

        self.assertEqual(longitude, -470000000)
        self.assertEqual(latitude, -228000000)


if __name__ == "__main__":
    unittest.main()
