"""Coleta de contadores e latências do simulador de carga.

Os valores ficam em memória durante a execução. O relatório final usa
`Statistics.summary()` e `Latency.summary()`; não há persistência de métricas
no banco nem exportação Prometheus a partir deste módulo.
"""

import math
from dataclasses import dataclass, field
from typing import List, Optional


def percentile(
    ordered: List[float],
    percentage: float,
) -> Optional[float]:
    """Calcula um percentil por posto mais próximo em uma lista ordenada.

    O cálculo é feito sobre as amostras já ordenadas para evitar custo extra a
    cada chamada. Não interpola valores: devolve uma amostra real, o que é
    suficiente para comparar cenários, mas não substitui um histograma
    Prometheus.

    Args:
        ordered: amostras em ordem crescente.
        percentage: percentil desejado entre 0 e 100.

    Returns:
        A amostra no percentil, ou None se a lista estiver vazia.

    Raises:
        ValueError: se o percentil estiver fora de 0..100.
    """
    if not 0 <= percentage <= 100:
        raise ValueError("Invalid percentile")

    if not ordered:
        return None

    # ceil garante que, por exemplo, p50 de duas amostras escolha a segunda.
    index = max(
        0,
        math.ceil(
            len(ordered) * percentage / 100
        ) - 1,
    )

    return ordered[index]


@dataclass
class Latency:
    """Acumula amostras de latência em milissegundos.

    O chamador registra segundos; a conversão para milissegundos acontece aqui
    para que o relatório use uma unidade só.
    """

    samples: List[float] = field(default_factory=list)

    def record(self, seconds: float) -> None:
        """Adiciona uma amostra de latência.

        Args:
            seconds: duração medida em segundos.

        Raises:
            ValueError: se o valor for negativo, infinito ou NaN.
        """
        if not math.isfinite(seconds) or seconds < 0:
            raise ValueError("Invalid latency")

        self.samples.append(seconds * 1000)

    def summary(self) -> dict:
        """Resume as amostras para o relatório JSON.

        Returns:
            Dicionário com contagem, mínimo, média, p50, p95, p99 e máximo.
            Com zero amostras, os valores são None e a contagem é 0.
        """
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

        # Ordena uma vez para calcular todos os percentis sobre a mesma cópia.
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
    """Contadores globais de conexões, pacotes, erros e latências do cenário."""

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
        """Registra uma conexão estabelecida e atualiza o pico simultâneo."""
        self.connections_established += 1
        self.connections_active += 1

        self.connections_peak = max(
            self.connections_peak,
            self.connections_active,
        )

    def disconnected(self) -> None:
        """Registra o encerramento de uma conexão ativa.

        Raises:
            RuntimeError: se o contador ficaria negativo, o que indicaria uma
                inconsistência no ciclo de vida das conexões.
        """
        if self.connections_active <= 0:
            raise RuntimeError(
                "Connection counter underflow"
            )

        self.connections_active -= 1

    def summary(self, elapsed: float) -> dict:
        """Monta o resumo final com taxas por segundo.

        Args:
            elapsed: duração total usada como denominador, em segundos.

        Returns:
            Estrutura pronta para serialização em JSON.

        Raises:
            ValueError: se a duração não for positiva.
        """
        if elapsed <= 0:
            raise ValueError("Invalid elapsed time")

        return {
            "elapsed_seconds": elapsed,
            # Conexões: tentativas, sucessos, falhas e o pico simultâneo.
            "connections": {
                "attempted": self.connections_attempted,
                "established": self.connections_established,
                "failed": self.connections_failed,
                "active": self.connections_active,
                "peak": self.connections_peak,
                "disconnects": self.disconnects,
                "reconnects": self.reconnects,
            },
            # Pacotes: o escritor só conta depois que o sendall termina; o ACK
            # é contabilizado separadamente quando o protocolo o possui.
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
