package com.omnicore.pke.engine.index;

import com.omnicore.pke.engine.vectorstore.VectorStorePort;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.CollectionRef;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorHit;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The configured {@link VectorStorePort}, with the (test) interceptors applied. All engine code uses this. */
@Component
public class Vectors {

    private final VectorStorePort port;
    private final List<VectorStoreInterceptor> interceptors;

    public Vectors(VectorStorePort port, ObjectProvider<VectorStoreInterceptor> interceptors) {
        this.port = port;
        this.interceptors = interceptors.orderedStream().toList();
    }

    public String provider() {
        return port.provider();
    }

    public void upsert(CollectionRef collection, List<VectorRecord> records) {
        before("upsert");
        port.upsert(collection, records);
    }

    public void delete(CollectionRef collection, Collection<String> ids) {
        before("delete");
        port.delete(collection, ids);
    }

    public List<VectorHit> search(CollectionRef collection, String query, int topK, double minScore, Map<String, Object> filter) {
        before("search");
        return port.search(collection, query, topK, minScore, filter);
    }

    public Set<String> existingIds(CollectionRef collection, Collection<String> ids) {
        before("existingIds");
        return port.existingIds(collection, ids);
    }

    public Set<String> allIds(CollectionRef collection) {
        before("allIds");
        return port.allIds(collection);
    }

    private void before(String operation) {
        interceptors.forEach(i -> i.before(operation));
    }
}
