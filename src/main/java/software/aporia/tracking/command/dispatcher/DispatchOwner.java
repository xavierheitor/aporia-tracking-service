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
 * Mantém o bloqueio exclusivo do emissor durante toda a sua vida. O UUID persistido no arquivo identifica
 * suas tentativas na recuperação após reinício.
 *
 * <p> Colaboradores: {@link CommandDispatcher} mantém o monitor desta instância durante tentativas e
 * recuperação; {@link software.aporia.tracking.storage.command.DeviceCommandRecoveryRepository} usa o UUID
 * para encerrar somente as transmissões deste emissor. </p>
 *
 * <p> Invariantes: o arquivo contém exatamente a representação ASCII de 36 caracteres de um UUID, gravada com
 * {@code force} antes de autorizar tentativas. O bloqueio do sistema operacional impede que outro processo
 * use o mesmo arquivo; {@link #openFiles} impede que a mesma JVM abra outro descritor para o mesmo inode,
 * pois fechar um descritor pode liberar o bloqueio POSIX de todo o processo. Fechar é idempotente e não apaga
 * o arquivo. </p>
 *
 * <p> Concorrência: {@link #open(Path)} e {@link #close()} sincronizam na classe para proteger {@link
 * #openFiles}. {@link #id()} exige que o chamador detenha o monitor da instância, garantindo que {@link
 * #close()} não libere o bloqueio durante a consulta. </p>
 */
public final class DispatchOwner implements AutoCloseable {
    /**
     * Arquivos já bloqueados nesta JVM, identificados pelo {@code fileKey} antes da abertura de outro
     * descritor. Fechar outro descritor do mesmo inode pode liberar o bloqueio POSIX do processo.
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
     * Abre o arquivo de propriedade com exclusividade e recupera o UUID persistido pelo emissor. Cria o
     * arquivo quando ele não existe e grava um UUID novo com {@code force} antes de retornar. O bloqueio é
     * mantido até {@link #close()}. A abertura é serializada na classe para proteger {@link #openFiles}.
     *
     * @param path caminho do arquivo persistente do emissor
     * @return proprietário com bloqueio exclusivo adquirido
     * @throws NullPointerException se o caminho for nulo
     * @throws IOException          se o arquivo não puder ser criado ou lido, se o
     *                              bloqueio estiver em uso, se o conteúdo não for
     *                              um UUID de 36 caracteres ou se o arquivo já
     *                              estiver aberto nesta JVM
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
        // O mesmo inode não pode ser aberto duas vezes nesta JVM; outro descritor
        // poderia liberar o bloqueio POSIX ao ser fechado.
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
     * Fornece o UUID do proprietário enquanto o bloqueio está válido. O chamador precisa deter o monitor
     * desta instância, o que garante que o fechamento não ocorra durante a consulta.
     *
     * @return UUID persistido do emissor
     * @throws IllegalStateException se o chamador não detiver o monitor do
     *                               proprietário ou se o bloqueio estiver inválido
     */
    public UUID id() {
        // O monitor é exigido para que close() não libere o bloqueio durante a consulta.
        if (!Thread.holdsLock(this))
            throw new IllegalStateException("DISPATCH_OWNER_LOCK_REQUIRED");
        if (!lock.isValid())
            throw new IllegalStateException("DISPATCH_OWNER_CLOSED");
        return id;
    }

    /**
     * Libera o arquivo de propriedade ao encerrar o emissor. Fecha o canal, o que libera o bloqueio, e remove
     * a identidade do arquivo de {@link #openFiles}. O arquivo não é apagado, para preservar o UUID entre
     * reinícios. É idempotente.
     *
     * @throws IOException se o fechamento do canal falhar
     */
    @Override
    public synchronized void close() throws IOException {
        synchronized (DispatchOwner.class) {
            if (!lock.channel().isOpen())
                return;
            // O canal é fechado antes de liberar o registro para outra abertura nesta JVM.
            lock.channel().close();
            // O arquivo permanece: o UUID precisa sobreviver ao reinício do processo.
            openFiles.remove(fileKey);
        }
    }
}
