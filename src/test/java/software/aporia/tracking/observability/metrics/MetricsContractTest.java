package software.aporia.tracking.observability.metrics;

import org.jspecify.annotations.NullMarked;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.aporia.tracking.command.dispatcher.CommandDispatcher.DispatchResult;
import software.aporia.tracking.storage.monitoring.QueueSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica o contrato dos medidores expostos: nomes, tags e o significado de valores ausentes
 * antes da primeira coleta.
 *
 * <p>Esses medidores alimentam painéis e alertas; trocar um nome ou uma tag quebra a observação
 * sem quebrar a compilação, por isso o teste fixa o contrato visível.</p>
 */
@NullMarked
class MetricsContractTest {

    /** Registro usado para inspecionar nomes e tags efetivamente exportados. */
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /**
     * Fecha o registro para não reutilizar medidores entre cenários.
     */
    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    /**
     * Mantém as métricas de fila sem valor até a primeira fotografia e preserva a última
     * fotografia quando uma coleta falha.
     *
     * <p>Falha de coleta não pode zerar a fila nem apagar o último retrato conhecido.</p>
     */
    @Test
    void keepsQueueUnavailableUntilFirstSnapshotAndPreservesItOnCollectionFailure() {
        var metrics = new QueueMetrics(registry);
        // NaN representa ausência de dado; zero seria confundido com fila vazia.
        assertEquals(0.0, registry.get("tracking.queue.snapshot.available").gauge().value());
        assertTrue(Double.isNaN(registry.get("tracking.queue.packets.pending").gauge().value()));
        assertTrue(Double.isNaN(metrics.snapshotAgeSeconds()));

        // A fotografia traz 7 pacotes pendentes e 2 comandos em queued entre os demais contadores.
        metrics.publish(new QueueSnapshot(7, 3, 4, 20, Map.of("queued", 2L), 1, 10));
        metrics.collectionFailed();

        // A falha conta erro, mas não apaga a última fotografia conhecida.
        assertEquals(1.0, registry.get("tracking.queue.snapshot.available").gauge().value());
        assertEquals(7.0, registry.get("tracking.queue.packets.pending").gauge().value());
        assertEquals(2.0, registry.get("tracking.queue.commands").tag("status", "queued").gauge().value());
        assertEquals(0.0, registry.get("tracking.queue.commands").tag("status", "sent").gauge().value());
        assertEquals(1.0, registry.get("tracking.queue.collection.errors").counter().count());
    }

    /**
     * Cada desfecho de dispatch tem contador e timer próprios.
     *
     * <p>Agregar desfechos diferentes esconderia falhas de entrega atrás de envios
     * bem-sucedidos.</p>
     */
    @Test
    void recordsEveryDispatchResultWithItsOwnCounterAndTimer() {
        var metrics = new CommandMetrics(registry);
        // Percorre todos os valores do enum para cobrir também desfechos adicionados no futuro.
        for (var result : DispatchResult.values()) {
            metrics.dispatchCompleted(result, 100);
        }
        for (var counter : registry.find("tracking.command.dispatch").counters()) {
            assertEquals(1.0, counter.count());
        }
        for (var timer : registry.find("tracking.command.dispatch.duration").timers()) {
            assertEquals(1L, timer.count());
        }
    }

    /**
     * Protocolo não previsto é agrupado na tag other.
     *
     * <p>A tag fixa limita a cardinalidade dos medidores; criar uma tag por protocolo
     * desconhecido permitiria crescimento sem limite.</p>
     */
    @Test
    void groupsUnknownProtocolsInOther() {
        var metrics = new GatewayMetrics(registry);
        metrics.packetIngested("future-protocol");
        assertEquals(1.0, registry.get("tracking.packets.ingested").tag("protocol", "other").counter().count());
        // A contagem não pode vazar para um protocolo suportado.
        assertEquals(0.0, registry.get("tracking.packets.ingested").tag("protocol", "suntech").counter().count());
    }
}
