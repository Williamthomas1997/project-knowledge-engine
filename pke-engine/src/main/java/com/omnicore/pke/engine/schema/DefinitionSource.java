package com.omnicore.pke.engine.schema;

import com.omnicore.pke.engine.schema.SchemaDefinitionParser.Format;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Reads the Git-versioned schema definitions. By default they come from the jar ({@code classpath:definitions/schema});
 * set {@code pke.definitions.dir} to read a working copy of {@code definitions/} instead.
 */
@Component
public class DefinitionSource {

    public record SourceFile(String location, String text, Format format) {
    }

    private final String externalDir;

    public DefinitionSource(@Value("${pke.definitions.dir:}") String externalDir) {
        this.externalDir = externalDir;
    }

    public List<SourceFile> schemaFiles() {
        return externalDir.isBlank() ? fromClasspath() : fromDirectory(Path.of(externalDir, "schema"));
    }

    private List<SourceFile> fromClasspath() {
        try {
            List<SourceFile> files = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:definitions/schema/**/*")) {
                Format format = formatOf(resource.getFilename());
                if (resource.isReadable() && format != null) {
                    files.add(new SourceFile(resource.getURL().toString(),
                            resource.getContentAsString(StandardCharsets.UTF_8), format));
                }
            }
            files.sort(Comparator.comparing(SourceFile::location));
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read classpath:definitions/schema", e);
        }
    }

    private List<SourceFile> fromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("pke.definitions.dir has no schema/ folder: " + dir.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> formatOf(p.getFileName().toString()) != null)
                    .sorted()
                    .map(p -> new SourceFile(p.toString(), read(p), formatOf(p.getFileName().toString())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Format formatOf(String filename) {
        if (filename == null) {
            return null;
        }
        if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
            return Format.YAML;
        }
        return filename.endsWith(".json") ? Format.JSON : null;
    }
}
