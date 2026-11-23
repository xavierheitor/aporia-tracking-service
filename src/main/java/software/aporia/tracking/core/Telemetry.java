package software.aporia.tracking.core;

import java.time.Instant;

/**
 * Reúne os campos de uma leitura. A presença de coordenadas permite distinguir uma posição de mensagens sem localização.
 *
 * @param deviceIdentity identidade do equipamento no protocolo
 * @param recordedAt instante da leitura no equipamento
 * @param latitude latitude em graus
 * @param longitude longitude em graus
 * @param speedKph velocidade em quilômetros por hora
 * @param ignition estado de ignição, quando informado
 * @param heading rumo em graus
 * @param satellites quantidade de satélites observados
 * @param validFix indicação de validade da posição GPS
 */
public record Telemetry(
        String deviceIdentity,
        Instant recordedAt,
        Double latitude,
        Double longitude,
        Double speedKph,
        Boolean ignition,
        Double heading,
        Integer satellites,
        Boolean validFix
        ) {

    /** Exige instante da leitura, ambas as coordenadas e indicação positiva de fixação GPS. */
     public boolean hasPosition() {
        return recordedAt != null
                && latitude != null
                && longitude != null
                && Boolean.TRUE.equals(validFix);
    }

}
