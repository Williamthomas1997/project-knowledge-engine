package com.omnicore.pke.engine.vectorstore;

import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorHit;
import com.omnicore.pke.engine.vectorstore.VectorStorePort.VectorRecord;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/** Mapping between the port's records and Spring AI documents and search requests, shared by the adapters. */
public final class SpringAiVectorSupport {

    private SpringAiVectorSupport() {
    }

    public static List<Document> documents(List<VectorRecord> records) {
        return records.stream().map(r -> new Document(r.id(), r.text(), r.metadata())).toList();
    }

    public static List<VectorHit> search(VectorStore store, String query, int topK, double minScore, Map<String, Object> filter) {
        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(topK).similarityThreshold(minScore);
        Filter.Expression expression = expression(filter);
        if (expression != null) {
            request.filterExpression(expression);
        }
        return store.similaritySearch(request.build()).stream()
                .map(d -> new VectorHit(d.getId(), String.valueOf(d.getMetadata().get("pke_id")),
                        d.getScore() == null ? 0 : d.getScore(), d.getText(), d.getMetadata()))
                .toList();
    }

    static Filter.Expression expression(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op op = null;
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(filter).entrySet()) {
            FilterExpressionBuilder.Op eq = b.eq(e.getKey(), e.getValue());
            op = op == null ? eq : b.and(op, eq);
        }
        return op.build();
    }
}
