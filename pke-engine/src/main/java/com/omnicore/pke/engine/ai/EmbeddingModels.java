package com.omnicore.pke.engine.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.omnicore.pke.engine.vectorstore.EmbeddingResolver;

/**
 * Resolves the embedding model of an embedding generation by name:
 * <ul>
 *   <li>{@code hashing-<dimensions>}: {@link HashingEmbeddingModel} (default {@code hashing-384});</li>
 *   <li>{@code spring-ai}: the configured Spring AI model (OpenAI under the {@code openai} profile).</li>
 * </ul>
 * Every model is wrapped so that only the document text is embedded, never its metadata.
 */
@Component
public class EmbeddingModels implements EmbeddingResolver {

    private static final Pattern HASHING = Pattern.compile("hashing-(\\d{2,5})");

    private final ObjectProvider<EmbeddingModel> springAi;
    private final String defaultModel;
    private final Map<String, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModels(ObjectProvider<EmbeddingModel> springAi,
                           @Value("${pke.embedding.model:hashing-384}") String defaultModel) {
        this.springAi = springAi;
        this.defaultModel = defaultModel;
    }

    /** The model new embedding generations use unless another is requested. */
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public EmbeddingModel resolve(String name) {
        return cache.computeIfAbsent(name, n -> new TextOnly(create(n)));
    }

    public int dimensions(String name) {
        return resolve(name).dimensions();
    }

    private EmbeddingModel create(String name) {
        Matcher hashing = HASHING.matcher(name);
        if (hashing.matches()) {
            return new HashingEmbeddingModel(Integer.parseInt(hashing.group(1)));
        }
        if ("spring-ai".equals(name)) {
            EmbeddingModel model = springAi.getIfAvailable();
            if (model == null) {
                throw new AiCallException("Embedding model 'spring-ai' requested but none is configured "
                        + "(start with the 'openai' profile)", false, null);
            }
            return model;
        }
        throw new IllegalArgumentException("Unknown embedding model '" + name + "' (hashing-<dimensions> or spring-ai)");
    }

    /** Embeds {@link Document#getText()} only; Spring AI's default would add the metadata to the embedded text. */
    private record TextOnly(EmbeddingModel delegate) implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            return delegate.embed(document.getText());
        }

        @Override
        public String getEmbeddingContent(Document document) {
            return document.getText();
        }

        @Override
        public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batching) {
            List<String> texts = documents.stream().map(Document::getText).toList();
            return delegate.call(new EmbeddingRequest(texts, options)).getResults().stream()
                    .map(org.springframework.ai.embedding.Embedding::getOutput).toList();
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }
    }
}
