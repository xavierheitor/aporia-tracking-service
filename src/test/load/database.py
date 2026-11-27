"""Provisionamento, snapshot e limpeza do benchmark de carga.

O módulo usa exclusivamente o banco `aporia_tracking_test`, o schema
`tracking_load` e o host local. `connect()` confirma esses três valores antes
de qualquer escrita; se o ambiente apontar para outro banco, a execução falha.

Cada execução cria um tenant próprio com nome reconhecível. A limpeza só apaga
um tenant cujo nome case com o padrão do benchmark, o que evita remover dados
reais por engano.
"""

import os
import secrets

from dataclasses import dataclass
from typing import List
from uuid import UUID, uuid4

import psycopg


DATABASE_NAME = "aporia_tracking_test"
DATABASE_USER = "tracking_test_admin"
DATABASE_HOST = "127.0.0.1"
DATABASE_PORT = 15437
DATABASE_SCHEMA = "tracking_load"


@dataclass(frozen=True)
class Device:
    """Dispositivo simulado com o ativo e a associação já provisionados."""

    id: UUID
    asset_id: UUID
    identity: str
    protocol: str
    codec: int


@dataclass(frozen=True)
class Fleet:
    """Tenant e dispositivos de uma execução do benchmark."""

    tenant_id: UUID
    devices: List[Device]


def connect():
    """Abre e valida uma conexão com o banco de testes.

    Returns:
        Conexão psycopg já autenticada no schema `tracking_load`.

    Raises:
        RuntimeError: se banco, usuário ou schema efetivos não forem os
            esperados. A mensagem não inclui senha.
        KeyError: se as variáveis de credencial não estiverem no ambiente.
    """
    connection = psycopg.connect(
        host=DATABASE_HOST,
        port=DATABASE_PORT,
        dbname=DATABASE_NAME,
        user=os.environ["TRACKING_TEST_DB_USER"],
        password=os.environ["TRACKING_TEST_DB_PASSWORD"],
        connect_timeout=5,
        # O search_path restringe a criação e a leitura ao schema do benchmark.
        options="-c search_path=tracking_load,public",
    )

    try:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT
                    current_database(),
                    current_user,
                    current_schema()
            """)

            actual = cursor.fetchone()

            expected = (
                DATABASE_NAME,
                DATABASE_USER,
                DATABASE_SCHEMA,
            )

            # Confirma o destino antes de qualquer INSERT/DELETE: um erro de
            # configuração não pode atingir desenvolvimento ou produção.
            if actual != expected:
                raise RuntimeError(
                    f"Unsafe database connection: {actual}"
                )

        connection.commit()

        return connection

    except BaseException:
        connection.close()
        raise


def generate_identity(protocol: str) -> str:
    """Gera uma identidade aleatória no formato aceito pelo protocolo.

    Args:
        protocol: `suntech` (10 dígitos) ou `teltonika` (15 dígitos).

    Returns:
        Identidade numérica em string.

    Raises:
        ValueError: se o protocolo não for suportado.
    """
    if protocol == "suntech":
        return "9" + f"{secrets.randbelow(10**9):09d}"

    if protocol == "teltonika":
        return "99" + f"{secrets.randbelow(10**13):013d}"

    raise ValueError(f"Unsupported protocol: {protocol}")


def provision_fleet(
    count: int,
    protocol: str,
) -> Fleet:
    """Cria tenant, dispositivos, ativos e associações para o cenário.

    A frota nasce pronta para ingestão: cada dispositivo tem um ativo
    associado desde um dia antes, então o worker encontra a associação temporal
    para qualquer leitura do benchmark.

    Args:
        count: quantidade de dispositivos, entre 1 e 100000.
        protocol: `suntech`, `teltonika` ou `mixed`.

    Returns:
        Frota com o tenant e os dispositivos criados.

    Raises:
        ValueError: se quantidade ou protocolo forem inválidos.
    """
    if not 1 <= count <= 100000:
        raise ValueError("Invalid fleet size")

    if protocol not in (
        "suntech",
        "teltonika",
        "mixed",
    ):
        raise ValueError("Invalid fleet protocol")

    tenant_id = uuid4()
    devices = []
    identities = set()

    for index in range(count):
        selected_protocol = protocol

        # Em "mixed", alterna os fabricantes para exercitar os dois decoders.
        if protocol == "mixed":
            selected_protocol = (
                "suntech"
                if index % 2 == 0
                else "teltonika"
            )

        # Repete a geração até achar uma identidade inédita nesta execução.
        while True:
            identity = generate_identity(selected_protocol)

            key = (selected_protocol, identity)

            if key not in identities:
                identities.add(key)
                break

        codec = 0

        # Alterna Codec 8 e 8E entre os Teltonika: são formatos de IO diferentes.
        if selected_protocol == "teltonika":
            codec = 0x08 if (index // 2) % 2 == 0 else 0x8E

        devices.append(
            Device(
                id=uuid4(),
                asset_id=uuid4(),
                identity=identity,
                protocol=selected_protocol,
                codec=codec,
            )
        )

    with connect() as connection:
        with connection.cursor() as cursor:
            # O nome do tenant é a marca usada pela limpeza segura.
            cursor.execute(
                """
                INSERT INTO tenants (id, name)
                VALUES (%s, %s)
                """,
                (
                    tenant_id,
                    f"Load Test {tenant_id}",
                ),
            )

            cursor.executemany(
                """
                INSERT INTO devices (
                    id,
                    tenant_id,
                    protocol,
                    identity
                )
                VALUES (%s, %s, %s, %s)
                """,
                [
                    (
                        device.id,
                        tenant_id,
                        device.protocol,
                        device.identity,
                    )
                    for device in devices
                ],
            )

            cursor.executemany(
                """
                INSERT INTO assets (
                    id,
                    tenant_id,
                    name
                )
                VALUES (%s, %s, %s)
                """,
                [
                    (
                        device.asset_id,
                        tenant_id,
                        f"Load Vehicle {index + 1}",
                    )
                    for index, device in enumerate(devices)
                ],
            )

            cursor.executemany(
                """
                INSERT INTO assignments (
                    id,
                    tenant_id,
                    device_id,
                    asset_id,
                    starts_at
                )
                VALUES (
                    %s, %s, %s, %s,
                    now() - interval '1 day'
                )
                """,
                [
                    (
                        uuid4(),
                        tenant_id,
                        device.id,
                        device.asset_id,
                    )
                    for device in devices
                ],
            )

    return Fleet(
        tenant_id=tenant_id,
        devices=devices,
    )


def snapshot(tenant_id: UUID) -> dict:
    """Conta as linhas do tenant nas tabelas do fluxo.

    Args:
        tenant_id: tenant do benchmark.

    Returns:
        Dicionário `nome_da_coluna -> contagem` para pacotes crus, posições,
        últimas posições, mensagens e eventos de outbox.
    """
    with connect() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                    (
                        SELECT count(*)
                        FROM raw_packets
                        WHERE tenant_id = %(tenant)s
                    ) AS raw_packets,
                    (
                        SELECT count(*)
                        FROM positions
                        WHERE tenant_id = %(tenant)s
                    ) AS positions,
                    (
                        SELECT count(*)
                        FROM last_positions
                        WHERE tenant_id = %(tenant)s
                    ) AS last_positions,
                    (
                        SELECT count(*)
                        FROM messages
                        WHERE tenant_id = %(tenant)s
                    ) AS messages,
                    (
                        SELECT count(*)
                        FROM outbox_events
                        WHERE tenant_id = %(tenant)s
                    ) AS outbox_events
                """,
                {"tenant": tenant_id},
            )

            values = cursor.fetchone()

            # Usa os nomes reais das colunas para não depender da ordem do SELECT
            # se a consulta ganhar novas métricas.
            return {
                column.name: value
                for column, value in zip(
                    cursor.description,
                    values,
                )
            }


def cleanup(tenant_id: UUID) -> None:
    """Remove todos os dados de um tenant de benchmark.

    A ordem das exclusões respeita as FKs: dependentes antes dos pais. O tenant
    é travado com `FOR UPDATE` e o nome precisa seguir o padrão do benchmark.

    Args:
        tenant_id: tenant criado por `provision_fleet`.

    Raises:
        RuntimeError: se o tenant existir mas não for um tenant de benchmark.
    """
    with connect() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT name
                FROM tenants
                WHERE id = %s
                FOR UPDATE
                """,
                (tenant_id,),
            )

            tenant = cursor.fetchone()

            if tenant is None:
                return

            # Confere a marca antes de qualquer DELETE: protege dados reais.
            if tenant[0] != f"Load Test {tenant_id}":
                raise RuntimeError(
                    "Refusing to delete a non-benchmark tenant"
                )

            # Filhos primeiro; a FK impede apagar um pai enquanto houver filho.
            tables = (
                "device_command_responses",
                "outbox_events",
                "last_positions",
                "positions",
                "messages",
                "raw_packets",
                "device_commands",
                "assignments",
                "assets",
            )

            for table in tables:
                cursor.execute(
                    psycopg.sql.SQL(
                        "DELETE FROM {} WHERE tenant_id = %s"
                    ).format(
                        psycopg.sql.Identifier(table)
                    ),
                    (tenant_id,),
                )

            # A descoberta usa o mesmo UUID do dispositivo; remove antes do cadastro.
            cursor.execute(
                """
                DELETE FROM device_discoveries
                WHERE id IN (
                    SELECT id
                    FROM devices
                    WHERE tenant_id = %s
                )
                """,
                (tenant_id,),
            )

            cursor.execute(
                "DELETE FROM devices WHERE tenant_id = %s",
                (tenant_id,),
            )

            cursor.execute(
                "DELETE FROM tenants WHERE id = %s",
                (tenant_id,),
            )
