package com.omnicore.pke.engine.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrdSectionsStepTest {

    @Test
    void shortSectionsStayWhole() {
        assertThat(BrdSectionsStep.split("  One paragraph.  ", 100)).containsExactly("One paragraph.");
    }

    @Test
    void longSectionsAreSplitAtParagraphBoundaries() {
        String paragraph = "x".repeat(30) + ".";
        String text = String.join("\n\n", paragraph, paragraph, paragraph, paragraph);
        List<String> parts = BrdSectionsStep.split(text, 70);
        assertThat(parts).hasSize(2).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(70));
        assertThat(String.join("\n\n", parts)).isEqualTo(text);
    }

    @Test
    void withoutBoundariesTheTextIsCutHard() {
        List<String> parts = BrdSectionsStep.split("y".repeat(250), 100);
        assertThat(parts).extracting(String::length).containsExactly(100, 100, 50);
    }
}
