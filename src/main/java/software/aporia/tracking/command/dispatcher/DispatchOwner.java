package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mantém o bloqueio exclusivo do emissor durante as escritas. O UUID persistido
 * identifica suas tentativas na recuperação após reinício.
 */
public final class DispatchOwner implements AutoCloseable {
    // Fechar outro descritor do mesmo inode pode liberar o bloqueio POSIX do
    // processo.
    /**
     * Arquivos já bloqueados nesta JVM, identificados antes da abertura de outro
     * descritor.
     */
    private static final Set<Object> openFiles = new HashSet<>();
    /**
     * Identidade do arquivo no sistema de arquivos, inclusive quando acessado por
     * outro caminho.
     */
    private final Object fileKey;
    /** Bloqueio do arquivo mantido durante a vida do emissor. */
    private final FileLock lock;
    /** UUID do emissor conservado entre reinícios. */
    private final UUID id;

    /**
     * Associa o bloqueio adquirido ao UUID persistido e à identidade do arquivo no
     * sistema
     * operacional.
     *
     * @param lock    bloqueio exclusivo adquirido sobre o arquivo do emissor
     * @param id      UUID persistido no arquivo do emissor
     * @param fileKey identidade do arquivo informada pelo sistema de arquivos
     */
    private DispatchOwner(FileLock lock, UUID id, Object fileKey) {
        this.fileKey = fileKey;
        this.lock = lock;
        this.id = id;
    }

    /**
     * Abre o arquivo de propriedade com exclusividade e recupera o UUID persistido
     * pelo emissor.
     *
     * @param path caminho do arquivo persistente do emissor
     * @return proprietário com bloqueio exclusivo adquirido
     * @throws IOException se a operação de entrada ou saída falhar
     */
    public static synchronized DispatchOwner open(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (FileAlreadyExistsException existing) {
            // O UUID no arquivo permite recuperar as transmissões deste emissor após
            // reinício.
        }
        Object fileKey = Files.readAttributes(path,
                BasicFileAttributes.class).fileKey();
        if (fileKey == null || openFiles.contains(fileKey))
            throw new IOException("DISPATCH_OWNER_UNAVAILABLE");
        var channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null)
                throw new IOException("DISPATCH_OWNER_IN_USE");
            UUID id;
            if (channel.size() == 0) {
                id = UUID.randomUUID();
                var bytes = StandardCharsets.US_ASCII.encode(id.toString());
                while (bytes.hasRemaining())
                    channel.write(bytes);
                // O UUID precisa chegar ao arquivo antes de autorizar tentativas que dependerão
                // dele após um reinício.
                channel.force(true);
            } else {
                if (channel.size() != 36)
                    throw new IOException("INVALID_DISPATCH_OWNER");
                var bytes = ByteBuffer.allocate(36);
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0)
                        throw new IOException("INVALID_DISPATCH_OWNER");
                }
                id = UUID.fromString(StandardCharsets.US_ASCII.decode(bytes.flip()).toString());
            }
            openFiles.add(fileKey);
            return new DispatchOwner(lock, id, fileKey);
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    /**
     * Fornece o UUID do proprietário enquanto o bloqueio está válido.
     *
     * @return UUID persistido do emissor
     * @throws IllegalStateException se o chamador não detiver o monitor do
     *                               proprietário ou se o bloqueio estiver inválido
     */
    public UUID id() {
        if (!Thread.holdsLock(this))
            throw new IllegalStateException("DISPATCH_OWNER_LOCK_REQUIRED");
        if (!lock.isValid())
            throw new IllegalStateException("DISPATCH_OWNER_CLOSED");
        return id;
    }

    /**
     * Libera o arquivo de propriedade ao encerrar o emissor.
     *
     * @throws IOException se a operação de entrada ou saída falhar
     */
    @Override
    public synchronized void close() throws IOException {
        synchronized (DispatchOwner.class) {
            if (!lock.channel().isOpen())
                return;
            lock.channel().close();
            openFiles.remove(fileKey);
        }
    }
}
