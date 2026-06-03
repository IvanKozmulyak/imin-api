package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.ReferenceImageSet;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads curated reference flyer images keyed by sub-style tag / vibe id and exposes them as
 * data URIs (for style_reference_images) or raw bytes (for Recraft training / OpenAI edits).
 *
 * Sources, merged into one {@code byTag} map at startup:
 *   - {@code poster-references.yaml} — the legacy 7 aesthetic tags (explicit files or folders).
 *   - {@code vibes.yaml} (via {@link VibeLibrary}) — each curated vibe's reference folder, keyed
 *     by vibe id, so {@code forTag(vibeId)} resolves the vibe's flyers. Text-only vibes are skipped.
 *
 * Folder / glob locators are expanded over the classpath, sorted by filename and capped at
 * {@code poster.references.max-per-tag}. This component does no LLM / network work.
 */
@Component
public class ReferenceImageLibrary {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageLibrary.class);

    private final ResourceLoader resourceLoader;
    private final PathMatchingResourcePatternResolver patternResolver;
    private final VibeLibrary vibeLibrary;
    private final String configFile;
    private final int maxPerTag;
    private Map<String, List<LoadedReference>> byTag = Collections.emptyMap();

    public ReferenceImageLibrary(
            ResourceLoader resourceLoader,
            VibeLibrary vibeLibrary,
            @Value("${poster.references.config-file:classpath:poster-references.yaml}") String configFile,
            @Value("${poster.references.max-per-tag:4}") int maxPerTag) {
        this.resourceLoader = resourceLoader;
        this.patternResolver = new PathMatchingResourcePatternResolver(resourceLoader);
        this.vibeLibrary = vibeLibrary;
        this.configFile = configFile;
        this.maxPerTag = maxPerTag;
    }

    @PostConstruct
    void load() {
        Map<String, List<LoadedReference>> resolved = new LinkedHashMap<>();
        Resource resource = resourceLoader.getResource(configFile);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(in);
                Object raw = root == null ? null : root.get("references");
                if (raw instanceof Map<?, ?> refs) {
                    resolved.putAll(resolveAll(refs));
                }
            } catch (IOException e) {
                log.error("Failed to load reference image config {}", configFile, e);
            }
        } else {
            log.warn("Reference image config not found at {} — only vibe references will be used", configFile);
        }
        // Curated vibes (vibes.yaml) contribute references keyed by vibe id, so forTag(vibeId)
        // resolves the vibe's flyers. Text-only vibes (empty references) are skipped.
        mergeVibeReferences(resolved);
        byTag = resolved;

        int totalLoaded = byTag.values().stream().mapToInt(List::size).sum();
        int nonEmptyTags = (int) byTag.values().stream().filter(l -> !l.isEmpty()).count();
        log.info("ReferenceImageLibrary loaded: {} tags, {} populated, {} total references",
                byTag.size(), nonEmptyTags, totalLoaded);
    }

    private void mergeVibeReferences(Map<String, List<LoadedReference>> target) {
        if (vibeLibrary == null) return;
        for (com.imin.iminapi.dto.Vibe v : vibeLibrary.all()) {
            if (v.references() == null || v.references().isEmpty()) continue;
            List<LoadedReference> resolved = new ArrayList<>();
            for (String locator : v.references()) {
                for (String expanded : expandEntry(locator)) {
                    try {
                        resolved.add(resolveOne(expanded));
                    } catch (IOException e) {
                        log.warn("Failed to load vibe reference '{}' for vibe '{}': {}",
                                expanded, v.id(), e.getMessage());
                    }
                }
            }
            if (!resolved.isEmpty()) {
                target.put(v.id(), resolved);
            } else {
                log.warn("Vibe '{}' declares references {} but none resolved", v.id(), v.references());
            }
        }
    }

    /**
     * Expand a single config locator into concrete image locators. A folder or {@code .../*} entry
     * is globbed (classpath) to its image files, sorted by filename and capped at {@code maxPerTag};
     * an explicit file or remote/data URI passes through unchanged.
     */
    private List<String> expandEntry(String entry) {
        String t = entry.trim();
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("data:")) {
            return List.of(t);
        }
        boolean looksLikeFile = hasImageExtension(t) && !t.endsWith("/*") && !t.endsWith("/");
        if (looksLikeFile) {
            return List.of(t);
        }
        String dir = t.endsWith("/*") ? t.substring(0, t.length() - 2)
                : t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
        String pattern = "classpath*:" + dir + "/*";
        try {
            Resource[] hits = patternResolver.getResources(pattern);
            return Arrays.stream(hits)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .filter(this::hasImageExtension)
                    .sorted()
                    .limit(maxPerTag)
                    .map(name -> dir + "/" + name)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to glob reference folder '{}': {}", pattern, e.getMessage());
            return List.of();
        }
    }

    private boolean hasImageExtension(String s) {
        String l = s.toLowerCase();
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
    }

    private Map<String, List<LoadedReference>> resolveAll(Map<?, ?> src) {
        Map<String, List<LoadedReference>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : src.entrySet()) {
            String tag = String.valueOf(e.getKey());
            List<LoadedReference> resolved = new ArrayList<>();
            if (e.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) continue;
                    for (String locator : expandEntry(item.toString())) {
                        try {
                            resolved.add(resolveOne(locator));
                        } catch (IOException ioe) {
                            log.warn("Failed to load reference '{}' for tag '{}': {}", locator, tag, ioe.getMessage());
                        }
                    }
                }
            }
            out.put(tag, resolved);
        }
        return out;
    }

    private LoadedReference resolveOne(String entry) throws IOException {
        String trimmed = entry.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return new LoadedReference(shortIdFromUrl(trimmed), trimmed, trimmed);
        }
        String locator = trimmed.startsWith("classpath:") ? trimmed : "classpath:" + trimmed;
        Resource r = resourceLoader.getResource(locator);
        if (!r.exists()) {
            throw new IOException("classpath resource not found: " + locator);
        }
        byte[] bytes;
        try (InputStream in = r.getInputStream()) {
            bytes = in.readAllBytes();
        }
        String mime = guessMime(trimmed);
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        String id = shortIdFromPath(trimmed);
        return new LoadedReference(id, dataUri, locator);
    }

    private String guessMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private String shortIdFromPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String shortIdFromUrl(String url) {
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    public ReferenceImageSet forTag(String subStyleTag) {
        List<LoadedReference> loaded = byTag.getOrDefault(subStyleTag, List.of());
        List<String> urls = loaded.stream().map(LoadedReference::urlOrDataUri).toList();
        List<String> ids = loaded.stream().map(LoadedReference::id).toList();
        return new ReferenceImageSet(subStyleTag, urls, ids);
    }

    public boolean hasTag(String subStyleTag) {
        return byTag.containsKey(subStyleTag);
    }

    public List<String> tags() {
        return List.copyOf(byTag.keySet());
    }

    public int referenceCount(String subStyleTag) {
        return byTag.getOrDefault(subStyleTag, List.of()).size();
    }

    public List<byte[]> loadAllBytes(String subStyleTag) {
        List<LoadedReference> refs = byTag.getOrDefault(subStyleTag, List.of());
        List<byte[]> out = new ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            try {
                out.add(bytesFor(refs.get(i)));
            } catch (Exception e) {
                log.warn("Could not materialize bytes for reference {}[{}]: {}",
                        subStyleTag, i, e.getMessage());
            }
        }
        return out;
    }

    public byte[] loadBytes(String subStyleTag, int index) {
        List<LoadedReference> refs = byTag.get(subStyleTag);
        if (refs == null) {
            throw new IllegalArgumentException("Unknown sub-style tag: " + subStyleTag);
        }
        if (index < 0 || index >= refs.size()) {
            throw new IllegalArgumentException(
                    "Index " + index + " out of range for tag " + subStyleTag + " (size=" + refs.size() + ")");
        }
        String locator = refs.get(index).sourceLocator();
        if (locator.startsWith("http://") || locator.startsWith("https://") || locator.startsWith("data:")) {
            throw new IllegalArgumentException(
                    "Reference for tag " + subStyleTag + "[" + index + "] is a remote URL, not a classpath resource");
        }
        Resource r = resourceLoader.getResource(locator);
        if (!r.exists()) {
            throw new IllegalStateException("Reference resource gone: " + locator);
        }
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read reference: " + locator, e);
        }
    }

    private byte[] bytesFor(LoadedReference ref) throws IOException {
        String locator = ref.sourceLocator();
        if (locator.startsWith("http://") || locator.startsWith("https://") || locator.startsWith("data:")) {
            return locator.getBytes();
        }
        Resource r = resourceLoader.getResource(locator);
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private record LoadedReference(String id, String urlOrDataUri, String sourceLocator) {}
}
