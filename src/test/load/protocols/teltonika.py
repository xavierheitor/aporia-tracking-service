"""Encoders Teltonika Codec 8 e Codec 8 Extended para o simulador de carga.

O módulo monta login (IMEI) e um lote AVL com uma posição. O CRC é o CRC-16/IBM
usado pelo protocolo; os ACKs são os bytes que o simulador espera de volta.
"""

from datetime import datetime
import re
import struct


CODEC_8 = 0x08
CODEC_8E = 0x8E

# O gateway responde 0x01 ao login e um inteiro de 4 bytes (1) ao lote.
LOGIN_ACK = b"\x01"
POSITION_ACK = struct.pack(">I", 1)


def crc16_ibm(data: bytes) -> int:
    """Calcula o CRC-16/IBM (polinômio 0xA001) do conteúdo.

    O CRC cobre o payload do lote, sem o cabeçalho de 8 bytes nem o próprio CRC.

    Args:
        data: bytes do payload AVL.

    Returns:
        CRC de 16 bits no intervalo 0..65535.
    """
    crc = 0

    for byte in data:
        crc ^= byte

        # Para cada um dos 8 bits, desloca e aplica o polinômio quando o bit
        # menos significativo está ligado.
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1

    return crc & 0xFFFF


def encode_login(imei: str) -> bytes:
    """Monta o pacote de login: tamanho do IMEI em 2 bytes + IMEI ASCII.

    Args:
        imei: 15 dígitos numéricos.

    Returns:
        Bytes do login, sem enquadramento adicional.

    Raises:
        ValueError: se o IMEI não tiver exatamente 15 dígitos.
    """
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
    """Monta um lote AVL com um registro de posição.

    Args:
        timestamp: instante da leitura; precisa ter fuso definido.
        latitude: latitude em graus, entre -90 e 90.
        longitude: longitude em graus, entre -180 e 180.
        speed: velocidade em km/h, entre 0 e 65535.
        ignition: estado da ignição no IO 239.
        codec: `CODEC_8` ou `CODEC_8E`.

    Returns:
        Quadro completo: preâmbulo, tamanho, payload e CRC.

    Raises:
        ValueError: se fuso, codec, coordenadas ou velocidade forem inválidos.
    """
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

    # Teltonika usa milissegundos desde a época Unix no registro.
    timestamp_ms = int(timestamp.timestamp() * 1000)

    # Longitude e latitude são inteiros escalados por 1e7, nessa ordem.
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

    # IO 239 (ignição) em um único byte; os demais contadores ficam zerados.
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

    # Preâmbulo (0), tamanho do payload, payload e CRC do payload.
    return (
        struct.pack(">II", 0, len(payload))
        + payload
        + struct.pack(">I", crc16_ibm(payload))
    )
