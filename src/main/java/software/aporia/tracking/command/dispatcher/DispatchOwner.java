package software.aporia.tracking.command.dispatcher;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** The exclusive file lock outlives every socket write authorized by this owner. */
public final class DispatchOwner implements AutoCloseable {
    // Closing a second descriptor for the same inode can drop POSIX process locks.
    private static final Set<Object> openFiles = new HashSet<>();
    private final Object fileKey;
    private final FileLock lock;
    private final UUID id;

    private DispatchOwner(FileLock lock, UUID id, Object fileKey) {
        this.fileKey = fileKey;
        this.lock = lock;
        this.id = id;
    }

    public static synchronized DispatchOwner open(Path path) throws IOException {
        try {
            Files.createFile(path);
        } catch (FileAlreadyExistsException existing) {
            // Reuse the stable owner after a restart.
        }
        Object fileKey = Files.readAttributes(path,
                BasicFileAttributes.class).fileKey();
        if (fileKey == null || openFiles.contains(fileKey)) throw new IOException("DISPATCH_OWNER_UNAVAILABLE");
        var channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("DISPATCH_OWNER_IN_USE");
            UUID id;
            if (channel.size() == 0) {
                id = UUID.randomUUID();
                var bytes = StandardCharsets.US_ASCII.encode(id.toString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            } else {
                if (channel.size() != 36) throw new IOException("INVALID_DISPATCH_OWNER");
                var bytes = ByteBuffer.allocate(36);
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) throw new IOException("INVALID_DISPATCH_OWNER");
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

    public UUID id() {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("DISPATCH_OWNER_LOCK_REQUIRED");
        if (!lock.isValid()) throw new IllegalStateException("DISPATCH_OWNER_CLOSED");
        return id;
    }

    @Override
    public synchronized void close() throws IOException {
        synchronized (DispatchOwner.class) {
            if (!lock.channel().isOpen()) return;
            lock.channel().close();
            openFiles.remove(fileKey);
        }
    }
}
