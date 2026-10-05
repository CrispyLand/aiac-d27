package com.crispyland.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Loads every file under classpath:docs/ as a LoadedDocument.
 * Files must be copied into src/main/resources/docs/ at build time.
 */
@Service
public class DocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(DocumentLoader.class);

    private final ResourcePatternResolver resolver;

    public DocumentLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public List<LoadedDocument> loadAll() {
        List<LoadedDocument> docs = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath:docs/**");
            for (Resource res : resources) {
                if (!res.isReadable()) continue;
                String filename = res.getFilename();
                if (filename == null) continue;

                String text;
                try {
                    text = res.getContentAsString(StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Could not read {}: {}", filename, e.getMessage());
                    continue;
                }
                if (text.isBlank()) continue;

                String fileType = fileType(filename);
                String sourceFile = extractRelativePath(res, filename);
                String module = moduleOf(sourceFile);

                docs.add(new LoadedDocument(sourceFile, module, fileType, text));
                log.debug("Loaded {} ({} chars)", sourceFile, text.length());
            }
        } catch (IOException e) {
            log.error("Could not scan classpath:docs/: {}", e.getMessage());
        }
        log.info("DocumentLoader: loaded {} documents", docs.size());
        return docs;
    }

    /** Extracts a path like "docs/com/crispyland/agent/Agent.java" from the resource URL. */
    private static String extractRelativePath(Resource res, String fallbackName) {
        try {
            String url = res.getURL().toString();
            int docsIdx = url.indexOf("/docs/");
            if (docsIdx >= 0) {
                return url.substring(docsIdx + 1); // "docs/com/crispyland/..."
            }
        } catch (IOException ignored) {}
        return "docs/" + fallbackName;
    }

    private static String fileType(String name) {
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".md"))   return "md";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yml";
        return "txt";
    }

    private static String moduleOf(String path) {
        if (path.contains("mcp-server"))     return "mcp-server";
        if (path.contains("weather-server")) return "weather-server";
        return "agent";
    }
}
