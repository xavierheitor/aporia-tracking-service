"""Encoder de posições Suntech STT para o simulador de carga.

O quadro gerado segue o mesmo formato aceito pelo `SuntechDecoder`: mensagem
ASCII terminada em CR, campos separados por ponto e vírgula e mapa de presença
fixo (`013831`) informando quais campos vieram.
"""

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
    """Monta um quadro STT com uma posição.

    Args:
        identity: identificação de 10 dígitos do equipamento.
        timestamp: instante da leitura; precisa ter fuso definido.
        latitude: latitude em graus, entre -90 e 90.
        longitude: longitude em graus, entre -180 e 180.
        speed: velocidade em km/h, não negativa.
        ignition: estado da ignição (entrada digital 1).

    Returns:
        Quadro ASCII pronto para envio no socket.

    Raises:
        ValueError: se identidade, fuso, coordenadas ou velocidade forem
            inválidos. O simulador falha cedo em vez de enviar lixo ao gateway.
    """
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

    # Normaliza para UTC antes de formatar: o decoder Suntech interpreta a
    # data/hora como UTC e acrescenta o sufixo Z.
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
