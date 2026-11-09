package com.omnicore.pke.engine.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Marks a test class that needs a real Neo4j. If none is available the class is reported as
 * <em>skipped</em> with the reason, never as passed. When one is available its data is wiped first.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresNeo4j.Condition.class)
public @interface RequiresNeo4j {

    class Condition implements ExecutionCondition, org.junit.jupiter.api.extension.BeforeAllCallback {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Neo4jTestDatabase.Connection c = Neo4jTestDatabase.connection();
            return Neo4jTestDatabase.available()
                    ? ConditionEvaluationResult.enabled("Using " + c.description())
                    : ConditionEvaluationResult.disabled(c.description());
        }

        @Override
        public void beforeAll(ExtensionContext context) {
            Neo4jTestDatabase.wipe();
        }
    }
}
