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

@Component
@Profile("gateway")
@NullMarked
public final class GatewayMetrics {

        private final Counter acceptedConnections;
        private final Counter closedConnections;
        private final Counter rejectedConnections;

        private final Counter receivedBytes;
        private final Counter protocolErrors;

        private final Map<String, @NonNull ProtocolMetrics> protocols;

        private record ProtocolMetrics(
                        Counter frames,
                        Counter received,
                        Counter ingested,
                        Counter ingestionFailed,
                        Counter reconnections,
                        Timer ingestionDuration) {
        }

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

                protocols = Objects.requireNonNull(Map.of(
                                "suntech",
                                registerProtocol(registry, "suntech"),
                                "teltonika",
                                registerProtocol(registry, "teltonika"),
                                "other",
                                registerProtocol(registry, "other")));
        }

        public void connectionAccepted() {
                acceptedConnections.increment();
        }

        public void connectionClosed() {
                closedConnections.increment();
        }

        public void connectionRejected() {
                rejectedConnections.increment();
        }

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

        private static Counter protocolCounter(
                        MeterRegistry registry,
                        String name,
                        String protocol) {

                return Counter.builder(name)
                                .tag("protocol", protocol)
                                .register(registry);
        }

        private ProtocolMetrics protocol(String protocol) {
                return Objects.requireNonNull(protocols.getOrDefault(
                                protocol,
                                Objects.requireNonNull(protocols.get("other"))));
        }

        public void bytesReceived(int bytes) {
                if (bytes > 0) {
                        receivedBytes.increment(bytes);
                }
        }

        public void frameReceived(String protocol) {
                protocol(protocol).frames().increment();
        }

        public void packetReceived(String protocol) {
                protocol(protocol).received().increment();
        }

        public void packetIngested(String protocol) {
                protocol(protocol).ingested().increment();
        }

        public void ingestionFailed(String protocol) {
                protocol(protocol).ingestionFailed().increment();
        }

        public void protocolError() {
                protocolErrors.increment();
        }

        public void deviceReconnected(String protocol) {
                protocol(protocol).reconnections().increment();
        }

        public void recordIngestionDuration(
                        String protocol,
                        long elapsedNanos) {

                protocol(protocol).ingestionDuration()
                                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
}
