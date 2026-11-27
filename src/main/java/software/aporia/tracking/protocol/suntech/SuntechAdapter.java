package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;

/**
 * Adapta os quadros ASCII Suntech ao contrato do gateway e extrai o modelo quando o mapa de
 * campos o inclui.
 *
 * <p>O adaptador não guarda estado e pode ser compartilhado entre conexões; cada conexão recebe uma {@link
 * SuntechSession} com o próprio buffer de enquadramento. O gateway usa {@link #describe(ProtocolFrame)}
 * somente na descoberta de um equipamento ainda não cadastrado.</p>
 */
public final class SuntechAdapter implements ProtocolAdapter {

    /**
     * Reconhece os prefixos ASCII que podem iniciar uma mensagem Suntech: {@code S} de {@code STT}, {@code A}
     * de {@code ALV}/{@code ASTT} e {@code R} de {@code RES}/{@code RPR}.
     *
     * <p>É apenas um filtro de seleção do adaptador; o envelope e a identidade ainda precisam ser validados
     * por {@link SuntechProtocol#identify(byte[])}.</p>
     *
     * @param first primeiro byte da conexão, interpretado sem sinal
     * @return verdadeiro quando o prefixo é compatível com o adaptador
     */
    @Override public boolean acceptsFirstByte(int first) { return first == 'S' || first == 'A' || first == 'R'; }

    /**
     * Fornece o identificador Suntech usado no cadastro e na persistência do pacote.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override
    public @NonNull String protocolId() {
        return "suntech";
    }

    /**
     * Informa a versão gravada junto ao pacote bruto para o worker escolher o decodificador.
     *
     * <p>O valor deve coincidir com o de {@link SuntechDecoder#decoderVersion()}; alterá-lo exige que o
     * decodificador correspondente continue registrado para os pacotes já persistidos.</p>
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    @Override
    public String decoderVersion() {
        return "suntech-ascii-v3";
    }

    /**
     * Abre um buffer de enquadramento próprio para esta conexão.
     *
     * @return sessão de protocolo com estado independente
     */
    @Override
    public ProtocolSession newSession() {
        return new SuntechSession();
    }

    /**
     * Valida o conteúdo e consulta o mapa de campos para extrair o código do modelo quando
     * presente.
     *
     * <p>A validação é feita executando o decodificador: um quadro posicional inválido, com mapa ou contagem
     * de campos incorreta, é rejeitado antes de virar pendência de autorização. Para {@code RES}/{@code RPR}
     * sem posição embutida, restam as checagens dos códigos de dois dígitos feitas abaixo.</p>
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return metadados que podem compor a pendência de autorização
     * @throws IllegalArgumentException se o quadro, o mapa ou os campos de descoberta forem
     *         inválidos
     */
    @Override
    public DeviceDescription describe(ProtocolFrame frame) {
        byte[] payload = frame.payload();
        // Decodificar aqui valida o frame inteiro; o resultado não é usado na descrição.
        new SuntechDecoder().decodePacket(payload, frame.deviceIdentity());
        // Remove o CR final e preserva campos vazios para não deslocar os índices do mapa.
        String[] fields = new String(payload, 0, payload.length - 1, StandardCharsets.US_ASCII).split(";", -1);
        // RES pode envolver uma mensagem STT/ASTT e deslocar seus campos uma posição.
        boolean embedded = "RES".equals(fields[0]) && fields.length > 2
                && ("STT".equals(fields[1]) || "ASTT".equals(fields[1]));
        String model = null;
        if (embedded || "STT".equals(fields[0]) || "ASTT".equals(fields[0])) {
            int mapIndex = embedded ? 3 : 2;
            // O bit 1 (máscara 2) anuncia o modelo logo depois do mapa de presença.
            // Só dígitos entram na descoberta; texto arbitrário é ignorado.
            if ((Integer.parseInt(fields[mapIndex], 16) & 2) != 0 && fields[mapIndex + 1].matches("[0-9]{1,10}")) {
                model = fields[mapIndex + 1];
            }
        } else if ("RES".equals(fields[0]) || "RPR".equals(fields[0])) {
            // Sem posição embutida, a descoberta exige códigos de operação de dois dígitos:
            // RPR usa o campo 3 e RES exige também o campo 2.
            if (fields.length < 4 || !fields[3].matches("[0-9]{2}")
                    || ("RES".equals(fields[0]) && !fields[2].matches("[0-9]{2}"))) {
                throw new IllegalArgumentException("INVALID_DISCOVERY_RESPONSE");
            }
        }
        return new DeviceDescription("Suntech", "gps_tracker", "Suntech ASCII", model);
    }

    /**
     * Mantém o enquadrador ASCII de uma conexão Suntech.
     *
     * <p>O buffer pertence a esta instância e não deve ser compartilhado entre sockets. A identidade é
     * extraída do envelope já validado por {@link SuntechProtocol}.</p>
     */
    private static final class SuntechSession
            implements ProtocolSession {

        /** Enquadrador Suntech usado para reconhecer a identidade do quadro. */
        private final SuntechProtocol protocol
                = new SuntechProtocol();

        /**
         * Converte cada mensagem delimitada por CR em um quadro com identidade e cabeçalho.
         *
         * @param incoming bytes disponíveis nesta leitura TCP, possivelmente um fragmento
         * @return quadros completos nesta leitura, em ordem de recepção
         * @throws IllegalArgumentException se o fluxo exceder o limite ou o quadro for inválido
         */
        @Override
        public List<ProtocolFrame> receive(byte[] incoming) {

            List<ProtocolFrame> frames = new ArrayList<>();

            for (byte[] payload : protocol.receive(incoming)) {

                // identify valida o envelope e garante ao menos um ';' antes da extração do tipo.
                String identity = protocol.identify(payload);

                String message = new String(
                        payload,
                        StandardCharsets.US_ASCII
                );

                int separator = message.indexOf(';');

                String messageType
                        = message.substring(0, separator);

                // O fluxo Suntech atual não envia ACK de recepção; o gateway só persiste o quadro.
                frames.add(new ProtocolFrame(
                        identity,
                        payload,
                        messageType,
                        false
                ));
            }

            return frames;
        }
    }
}
