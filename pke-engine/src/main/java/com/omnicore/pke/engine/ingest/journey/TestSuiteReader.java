package com.omnicore.pke.engine.ingest.journey;

import com.omnicore.pke.engine.extract.ExtractedKnowledge;
import com.omnicore.pke.engine.store.KnowledgeChanges.EntityRef;
import com.omnicore.pke.engine.store.KnowledgeChanges.EvidenceInput;
import com.omnicore.pke.spi.journey.JourneyDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * TestNG suite XMLs: a suite lists test classes in order. Each suite becomes a {@code TestSuite} that
 * {@code COVERS} every journey driven by one of its classes.
 */
public final class TestSuiteReader {

    public static final String METHOD = "journey.testng-suite";

    public record Suite(String name, String file, List<String> classes) {
    }

    /** The suite in {@code file}, or empty when it is not a TestNG suite. */
    public static Optional<Suite> read(Path root, String file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(root.resolve(file).toFile());
            Element suite = doc.getDocumentElement();
            if (!"suite".equals(suite.getTagName())) {
                return Optional.empty();
            }
            List<String> classes = new ArrayList<>();
            NodeList nodes = suite.getElementsByTagName("class");
            for (int i = 0; i < nodes.getLength(); i++) {
                String name = ((Element) nodes.item(i)).getAttribute("name");
                if (!name.isBlank() && !classes.contains(name)) {
                    classes.add(name);
                }
            }
            return Optional.of(new Suite(suite.getAttribute("name"), file, classes));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static ExtractedKnowledge map(Suite suite, List<JourneyDefinition> journeys, String repository,
                                         String artifactKey, String commit) {
        ExtractedKnowledge k = new ExtractedKnowledge(suite.file());
        EvidenceInput evidence = EvidenceInput.scoped(artifactKey, commit, suite.file(), suite.file(), METHOD, suite.name());
        EntityRef ref = k.entity("TestSuite", Map.of("repository", repository, "name", suite.name(), "file", suite.file(),
                "classes", suite.classes()), List.of("repository", "name"), evidence);
        for (JourneyDefinition journey : journeys) {
            if (journey.drivers().stream().anyMatch(suite.classes()::contains)) {
                k.relate("COVERS", ref, EntityRef.key("UserJourney", Map.of("journeyKey", journey.key())), Map.of(), evidence, null);
            }
        }
        return k;
    }
}
