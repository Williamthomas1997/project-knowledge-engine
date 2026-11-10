package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.ai.EmbeddingModels;
import com.omnicore.pke.engine.graph.VectorCatalogStore;
import com.omnicore.pke.engine.graph.VectorCatalogStore.Generation;
import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Embedding generations (ADR-007 §3): a model + dimensions. Every generation has its own physical collections,
 * so dimensions are never mixed. Lifecycle: {@code BUILDING → READY → ACTIVE → RETIRED}. Searches use the ACTIVE
 * generation; the index worker writes to every non-retired generation. Re-embedding never creates knowledge events.
 */
@Service
public class EmbeddingGenerations {

    static final Set<String> WRITABLE = Set.of("ACTIVE", "BUILDING", "READY");

    private final VectorCatalogStore store;
    private final EmbeddingModels models;

    public EmbeddingGenerations(VectorCatalogStore store, EmbeddingModels models) {
        this.store = store;
        this.models = models;
    }

    /** Creates generation {@code g1} with the default model when there is none. */
    public synchronized Generation ensureActive() {
        Optional<Generation> active = activeOptional();
        if (active.isPresent()) {
            return active.get();
        }
        String model = models.defaultModel();
        store.createGeneration(nextId(), model, models.dimensions(model), "ACTIVE");
        return activeOptional().orElseThrow();
    }

    public Generation active() {
        return activeOptional().orElseGet(this::ensureActive);
    }

    public List<Generation> writable() {
        return store.generations().stream().filter(g -> WRITABLE.contains(g.status())).toList();
    }

    public List<Generation> all() {
        return store.generations();
    }

    public Generation get(String id) {
        return store.generation(id).orElseThrow(() -> new NotFoundException("Embedding generation " + id + " does not exist"));
    }

    /** A new BUILDING generation; the caller starts the build. */
    public synchronized Generation create(String model) {
        String name = model == null || model.isBlank() ? models.defaultModel() : model;
        int dimensions = models.dimensions(name);
        if (store.generations().stream().anyMatch(g -> "BUILDING".equals(g.status()))) {
            throw new ConflictException("Another embedding generation is still building");
        }
        String id = nextId();
        store.createGeneration(id, name, dimensions, "BUILDING");
        return get(id);
    }

    void markReady(String id) {
        store.setGenerationStatus(id, "READY", null);
    }

    void activate(String id, String evaluationJson) {
        store.setGenerationStatus(id, "ACTIVE", evaluationJson);
    }

    void recordEvaluation(String id, String status, String evaluationJson) {
        store.setGenerationStatus(id, status, evaluationJson);
    }

    private Optional<Generation> activeOptional() {
        return store.generations().stream().filter(g -> "ACTIVE".equals(g.status())).findFirst();
    }

    private String nextId() {
        return "g" + (store.generations().size() + 1);
    }
}
