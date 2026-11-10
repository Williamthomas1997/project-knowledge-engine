package com.omnicore.pke.engine.retrieval;

import java.util.List;

/**
 * Optional reranker port (e.g. a cross-encoder, as in Graphiti). The default keeps the fused order. A bean of this
 * type replaces it.
 */
public interface Reranker {

    List<RetrievalService.Hit> rerank(String query, List<RetrievalService.Hit> hits);

    Reranker NONE = (query, hits) -> hits;
}
