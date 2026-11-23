package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

public final class SuntechMessageCapabilities {

    private SuntechMessageCapabilities() {
    }

    public static ProtocolMessageCapabilities classify(
            byte[] payload
    ) {

        if (payload == null || payload.length == 0) {
            return new ProtocolMessageCapabilities(Set.of());
        }

        String message = new String(
                payload,
                StandardCharsets.US_ASCII
        ).strip();

        String[] fields = message.split(";", -1);

        if (fields.length == 0) {
            return new ProtocolMessageCapabilities(Set.of());
        }

        EnumSet<MessageCapability> capabilities
                = EnumSet.noneOf(MessageCapability.class);

        switch (fields[0]) {

            case "STT", "ASTT" ->
                capabilities.add(MessageCapability.TELEMETRY);

            case "ALV" ->
                capabilities.add(MessageCapability.HEARTBEAT);

            case "RPR" ->
                capabilities.add(MessageCapability.COMMAND_RESPONSE);

            case "RES" -> {
                capabilities.add(MessageCapability.COMMAND_RESPONSE);

                if (fields.length > 1
                        && ("STT".equals(fields[1])
                        || "ASTT".equals(fields[1]))) {

                    capabilities.add(MessageCapability.TELEMETRY);
                }
            }

            default -> {
                // Mensagem ainda não reconhecida.
            }
        }

        return new ProtocolMessageCapabilities(capabilities);
    }
}
