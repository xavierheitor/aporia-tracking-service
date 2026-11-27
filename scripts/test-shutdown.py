"""Simula o desligamento do gateway com um quadro Suntech incompleto.

Conecta ao listener local, envia um quadro completo e depois só a primeira
metade de outro. O CR da segunda metade fica retido; o gateway precisa manter o
fragmento em memória e, ao ser desligado, não pode confirmar o pacote como se
estivesse completo.
"""

import socket
import time

# Endereço do gateway local; ajuste se o listener estiver em outra porta.
HOST = "127.0.0.1"
PORT = 5500

FRAME = (
    b"STT;1234567890;013831;20261120;08:45:00;"
    b"-16.6700;-49.2500;45.5;1\r"
)

with socket.create_connection((HOST, PORT), timeout=5) as sock:
    sock.settimeout(None)

    print("Rastreador conectado.", flush=True)

    # Primeiro pacote completo
    sock.sendall(FRAME)
    print("Pacote 1 enviado integralmente.", flush=True)

    time.sleep(2)

    # O CR permanece na segunda metade. O gateway precisa guardar o fragmento até receber o delimitador.
    midpoint = len(FRAME) // 2

    sock.sendall(FRAME[:midpoint])
    print("Primeira metade do pacote 2 enviada.", flush=True)

    # Dá tempo para o operador executar o shutdown do gateway enquanto o
    # buffering permanece pendente.
    time.sleep(10)

    try:
        sock.sendall(FRAME[midpoint:])
        print("Segunda metade do pacote 2 enviada.", flush=True)
    except OSError as error:
        # Se o gateway fechou o socket, a segunda metade não chega: é o
        # comportamento esperado durante o desligamento.
        print(f"Falha ao completar pacote 2: {error}", flush=True)

print("Simulação encerrada.")
