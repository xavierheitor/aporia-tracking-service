package software.aporia.tracking.observability.metrics;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Registra o ciclo das conexões TCP e a ingestão por protocolo no perfil {@code gateway}.
 *
 * <p>Os contadores de conexões e de erros de protocolo são globais. As métricas de frames, pacotes,
 * reconexões e duração de ingestão têm a tag {@code protocol} limitada a {@code suntech}, {@code teltonika} e
 * {@code other}; protocolos desconhecidos são agrupados em {@code other} para evitar cardinalidade ilimitada.
 * O mapa de medidores é criado no construtor e não muda depois.</p>
 *
 * <p>O bean é singleton e os medidores do Micrometer são thread-safe; os métodos podem ser chamados pelas
 * threads de conexão do gateway. Os contadores acumulam desde a criação do medidor e não distinguem tenant ou
 * dispositivo.</p>
 */
@Component
@Profile("gateway")
@NullMarked
public final class GatewayMetrics {

        /** Conexões aceitas pelo listener. */
        private final Counter acceptedConnections;
        /** Conexões encerradas, incluindo as rejeitadas. */
        private final Counter closedConnections;
        /** Conexões aceitas e fechadas sem entregar o socket ao handler. */
        private final Counter rejectedConnections;

        /** Bytes lidos dos sockets, contabilizados apenas quando positivos. */
        private final Counter receivedBytes;
        /** Falhas de enquadramento ou de sessão do protocolo. */
        private final Counter protocolErrors;

        /** Medidores por protocolo, com chaves fixas; consultas fora do mapa usam {@code other}. */
        private final Map<String, @NonNull ProtocolMetrics> protocols;

        /**
         * Agrupa os medidores de um protocolo para evitar busca por nome a cada registro.
         *
         * @param frames            frames recebidos
         * @param received          pacotes recebidos
         * @param ingested          pacotes persistidos com sucesso
         * @param ingestionFailed   pacotes rejeitados na ingestão
         * @param reconnections     reconexões identificadas
         * @param ingestionDuration duração da ingestão
         */
        private record ProtocolMetrics(
                        Counter frames,
                        Counter received,
                        Counter ingested,
                        Counter ingestionFailed,
                        Counter reconnections,
                        Timer ingestionDuration) {
        }

        /**
         * Cria os contadores globais e os medidores dos três protocolos previstos.
         *
         * @param registry registro Micrometer do contexto
         */
        public GatewayMetrics(MeterRegistry registry) {

                acceptedConnections = Counter.builder(
                                "tracking.tcp.connections.accepted")
                                .description("Total accepted TCP connections")
                                .register(registry);

                closedConnections = Counter.builder(
                                "tracking.tcp.connections.closed")
                                .description("Total closed TCP connections")
                                .register(registry);

                rejectedConnections = Counter.builder(
                                "tracking.tcp.connections.rejected")
                                .description("TCP connections rejected by the gateway")
                                .register(registry);

                receivedBytes = Counter.builder("tracking.tcp.received")
                                .description("Total bytes read from TCP connections")
                                .baseUnit("bytes")
                                .register(registry);

                protocolErrors = Counter.builder("tracking.protocol.errors")
                                .description("Invalid protocol or session frames")
                                .register(registry);

                // Apenas os protocolos previstos são indexados; o restante cai em "other".
                protocols = Objects.requireNonNull(Map.of(
                                "suntech",
                                registerProtocol(registry, "suntech"),
                                "teltonika",
                                registerProtocol(registry, "teltonika"),
                                "other",
                                registerProtocol(registry, "other")));
        }

        /**
         * Registra uma conexão aceita pelo listener.
         */
        public void connectionAccepted() {
                acceptedConnections.increment();
        }

        /**
         * Registra o fechamento de uma conexão, inclusive quando ela foi rejeitada.
         */
        public void connectionClosed() {
                closedConnections.increment();
        }

        /**
         * Registra uma conexão aceita que foi fechada sem ser entregue ao handler.
         */
        public void connectionRejected() {
                rejectedConnections.increment();
        }

        /**
         * Cria os medidores de um protocolo com a tag correspondente.
         *
         * @param registry registro Micrometer do contexto
         * @param protocol nome usado na tag {@code protocol}
         * @return conjunto de medidores daquele protocolo
         */
        private static ProtocolMetrics registerProtocol(
                        MeterRegistry registry,
                        String protocol) {

                return new ProtocolMetrics(
                                protocolCounter(
                                                registry,
                                                "tracking.protocol.frames.received",
                                                protocol),

                                protocolCounter(
                                                registry,
                                                "tracking.packets.received",
                                                protocol),

                                protocolCounter(
                                                registry,
                                                "tracking.packets.ingested",
                                                protocol),

                                protocolCounter(
                                                registry,
                                                "tracking.packets.ingestion.failed",
                                                protocol),

                                protocolCounter(
                                                registry,
                                                "tracking.device.reconnections",
                                                protocol),

                                Timer.builder("tracking.packet.ingestion")
                                                .description("Packet ingestion duration")
                                                .tag("protocol", protocol)
                                                .publishPercentileHistogram()
                                                .register(registry));
        }

        /**
         * Cria um contador de protocolo sem descrição própria.
         *
         * @param registry registro Micrometer do contexto
         * @param name     nome do contador
         * @param protocol valor da tag {@code protocol}
         * @return contador registrado
         */
        private static Counter protocolCounter(
                        MeterRegistry registry,
                        String name,
                        String protocol) {

                return Counter.builder(name)
                                .tag("protocol", protocol)
                                .register(registry);
        }

        /**
         * Resolve os medidores do protocolo, usando {@code other} quando ele não é previsto.
         *
         * @param protocol protocolo informado pelo chamador
         * @return medidores do protocolo ou os medidores agregados de {@code other}
         */
        private ProtocolMetrics protocol(String protocol) {
                return Objects.requireNonNull(protocols.getOrDefault(
                                protocol,
                                Objects.requireNonNull(protocols.get("other"))));
        }

        /**
         * Registra bytes lidos de um socket. Leituras negativas, como o fim de stream, são ignoradas.
         *
         * @param bytes quantidade de bytes lidos
         */
        public void bytesReceived(int bytes) {
                if (bytes > 0) {
                        receivedBytes.increment(bytes);
                }
        }

        /**
         * Registra um frame recebido para o protocolo informado.
         *
         * @param protocol protocolo do frame
         */
        public void frameReceived(String protocol) {
                protocol(protocol).frames().increment();
        }

        /**
         * Registra um pacote recebido para o protocolo informado.
         *
         * @param protocol protocolo do pacote
         */
        public void packetReceived(String protocol) {
                protocol(protocol).received().increment();
        }

        /**
         * Registra um pacote persistido com sucesso na ingestão.
         *
         * @param protocol protocolo do pacote
         */
        public void packetIngested(String protocol) {
                protocol(protocol).ingested().increment();
        }

        /**
         * Registra uma falha de ingestão para o protocolo informado.
         *
         * @param protocol protocolo do pacote
         */
        public void ingestionFailed(String protocol) {
                protocol(protocol).ingestionFailed().increment();
        }

        /**
         * Registra uma falha de protocolo ou de sessão antes da ingestão.
         */
        public void protocolError() {
                protocolErrors.increment();
        }

        /**
         * Registra a substituição da sessão anterior de um dispositivo.
         *
         * @param protocol protocolo da sessão que reconectou
         */
        public void deviceReconnected(String protocol) {
                protocol(protocol).reconnections().increment();
        }

        /**
         * Registra a duração de uma tentativa de ingestão, inclusive quando ela falhou.
         *
         * @param protocol     protocolo do pacote
         * @param elapsedNanos duração da ingestão em nanossegundos
         */
        public void recordIngestionDuration(
                        String protocol,
                        long elapsedNanos) {

                protocol(protocol).ingestionDuration()
                                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
}
