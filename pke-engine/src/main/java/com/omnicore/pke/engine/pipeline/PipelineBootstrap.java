package com.omnicore.pke.engine.pipeline;

import com.omnicore.pke.engine.pipeline.skill.SkillRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Loads skills, then pipelines (which reference skills), after the schema bootstrap. */
@Component
@Order(30)
public class PipelineBootstrap implements ApplicationRunner {

    private final SkillRegistry skills;
    private final PipelineRegistry pipelines;

    public PipelineBootstrap(SkillRegistry skills, PipelineRegistry pipelines) {
        this.skills = skills;
        this.pipelines = pipelines;
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    public void load() {
        skills.load();
        pipelines.load();
    }
}
