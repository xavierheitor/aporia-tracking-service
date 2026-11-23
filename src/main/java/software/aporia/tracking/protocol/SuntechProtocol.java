package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class SuntechProtocol {

    private static final int MAX_FRAME_SIZE = 8192;
    private static final byte DELIMITER = '\r';

    private static final Set<String> HEADERS
            = Set.of("ALV", "STT", "ASTT", "RES", "RPR");

    private byte[] buffer = new byte[0];

    public List<byte[]> receive(byte[] incoming) {

        if (incoming == null) {
            throw new IllegalArgumentException("NULL_INPUT");
        }

        List<byte[]> frames = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < incoming.length; i++) {

            if (incoming[i] == DELIMITER) {

                int frameSize = buffer.length + i - start + 1;

                if (frameSize > MAX_FRAME_SIZE) {
                    throw new IllegalArgumentException(
                            "FRAME_TOO_LARGE"
                    );
                }

                byte[] frame = Arrays.copyOf(
                        buffer,
                        frameSize
                );

                System.arraycopy(
                        incoming,
                        start,
                        frame,
                        buffer.length,
                        i - start + 1
                );

                frames.add(frame);

                buffer = new byte[0];
                start = i + 1;

            } else if (buffer.length + i - start + 1
                    >= MAX_FRAME_SIZE) {

                throw new IllegalArgumentException(
                        "FRAME_TOO_LARGE"
                );
            }
        }

        int remaining = incoming.length - start;

        byte[] remainder = Arrays.copyOf(
                buffer,
                buffer.length + remaining
        );

        System.arraycopy(
                incoming,
                start,
                remainder,
                buffer.length,
                remaining
        );

        buffer = remainder;

        return frames;
    }

    public String identify(byte[] frame) {

        if (frame == null
                || frame.length < 2
                || frame.length > MAX_FRAME_SIZE
                || frame[frame.length - 1] != DELIMITER) {

            throw new IllegalArgumentException("INVALID_FRAME");
        }

        for (int i = 0; i < frame.length - 1; i++) {
            if (frame[i] < 32 || frame[i] > 126) {
                throw new IllegalArgumentException("INVALID_FRAME");
            }
        }

        String message = new String(
                frame,
                0,
                frame.length - 1,
                StandardCharsets.US_ASCII
        );

        String[] parts = message.split(";", -1);

        if (parts.length < 2 || !HEADERS.contains(parts[0])) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MESSAGE"
            );
        }

        int identityIndex = 1;

        if ("RES".equals(parts[0])
                && parts.length > 1
                && ("STT".equals(parts[1])
                || "ASTT".equals(parts[1]))) {

            identityIndex = 2;
        }

        if (parts.length <= identityIndex) {
            throw new IllegalArgumentException(
                    "INVALID_IDENTITY"
            );
        }

        String identity = parts[identityIndex];

        if (!identity.matches("\\d{10}")) {
            throw new IllegalArgumentException(
                    "INVALID_IDENTITY"
            );
        }

        return identity;
    }

}
