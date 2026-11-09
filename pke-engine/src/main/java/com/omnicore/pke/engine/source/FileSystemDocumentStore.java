package com.omnicore.pke.engine.source;

import com.omnicore.pke.engine.kernel.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentStore} on the local file system: {@code <pke.data-dir>/blobs/sha256/ab/abcdef...}. Writes go to a
 * temporary file first and are moved into place, so a crash never leaves a partial blob under its final name.
 */
@Component
public class FileSystemDocumentStore implements DocumentStore {

    private static final Pattern REF = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final Path root;

    public FileSystemDocumentStore(@Value("${pke.data-dir:./data}") String dataDir) {
        this.root = Path.of(dataDir).toAbsolutePath().resolve("blobs").resolve("sha256");
    }

    @Override
    public Stored put(byte[] content) {
        String sha = sha256(content);
        Path target = path(sha);
        if (!Files.exists(target)) {
            try {
                Files.createDirectories(target.getParent());
                Path temp = Files.createTempFile(target.getParent(), sha, ".tmp");
                Files.write(temp, content);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not store document " + sha, e);
            }
        }
        return new Stored("sha256:" + sha, sha, content.length);
    }

    @Override
    public InputStream open(String ref) {
        if (!exists(ref)) {
            throw new NotFoundException("Document " + ref + " is not stored");
        }
        try {
            return Files.newInputStream(path(ref.substring("sha256:".length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String ref) {
        return ref != null && REF.matcher(ref).matches() && Files.isRegularFile(path(ref.substring("sha256:".length())));
    }

    private Path path(String sha) {
        return root.resolve(sha.substring(0, 2)).resolve(sha);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
