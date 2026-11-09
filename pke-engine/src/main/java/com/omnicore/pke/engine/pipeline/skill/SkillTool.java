package com.omnicore.pke.engine.pipeline.skill;

import java.util.Map;

/**
 * A read-only engine capability a skill may use, such as finding similar cards or searching code. Steps call
 * tools through {@link SkillRunner#tool}, which refuses any tool the skill did not declare in its front matter.
 * In M3 tools gather context before the model call; model-driven tool calling is not enabled.
 */
public interface SkillTool {

    /** Kebab-case name used in {@code tools:}. */
    String name();

    String description();

    Map<String, Object> call(Map<String, Object> arguments);
}
