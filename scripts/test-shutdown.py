
import socket
import time

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

    # Segundo pacote dividido em duas transmissões TCP
    midpoint = len(FRAME) // 2

    sock.sendall(FRAME[:midpoint])
    print("Primeira metade do pacote 2 enviada.", flush=True)

    # Janela para executar o shutdown
    time.sleep(10)

    try:
        sock.sendall(FRAME[midpoint:])
        print("Segunda metade do pacote 2 enviada.", flush=True)
    except OSError as error:
        print(f"Falha ao completar pacote 2: {error}", flush=True)

print("Simulação encerrada.")
