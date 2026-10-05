package com.changelogengine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class PomParserService {

    private static final Logger log = LoggerFactory.getLogger(PomParserService.class);

    // Java 17 Record replaces @Data and @Builder completely
    public record MavenDependency(
            String groupId,
            String artifactId,
            String version,
            String scope
    ) {}

    /**
     * Parses an incoming pom.xml InputStream and extracts all declared dependencies.
     */
    public List<MavenDependency> parseDependencies(InputStream pomInputStream) {
        List<MavenDependency> dependencies = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Guard against XML External Entity (XXE) attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomInputStream);
            doc.getDocumentElement().normalize();

            NodeList dependencyNodes = doc.getElementsByTagName("dependency");

            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Node node = dependencyNodes.item(i);

                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;

                    String groupId = getTagValue("groupId", element);
                    String artifactId = getTagValue("artifactId", element);
                    String version = getTagValue("version", element);
                    String scope = getTagValue("scope", element);

                    dependencies.add(new MavenDependency(
                            groupId,
                            artifactId,
                            version != null ? version : "MANAGED_BY_PARENT",
                            scope != null ? scope : "compile"
                    ));
                }
            }
            log.info("Successfully parsed {} dependencies from pom.xml", dependencies.size());
        } catch (Exception e) {
            log.error("Failed to parse pom.xml input stream", e);
            throw new RuntimeException("Invalid pom.xml file format", e);
        }

        return dependencies;
    }

    private String getTagValue(String tag, Element element) {
        NodeList nodeList = element.getElementsByTagName(tag);
        if (nodeList != null && nodeList.getLength() > 0) {
            Node node = nodeList.item(0);
            if (node != null) {
                return node.getTextContent().trim();
            }
        }
        return null;
    }
}