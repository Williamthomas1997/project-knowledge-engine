package com.omnicore.pke.engine.vectorstore;

import com.omnicore.pke.engine.support.KnowledgeIT;
import org.springframework.beans.factory.annotation.Autowired;

/** The vector store contract against the default adapter: the Neo4j vector index of the test database. */
class Neo4jVectorStoreContractIT extends KnowledgeIT implements VectorStoreContract {

    @Autowired
    VectorStorePort port;

    @Override
    public VectorStorePort port() {
        return port;
    }
}
