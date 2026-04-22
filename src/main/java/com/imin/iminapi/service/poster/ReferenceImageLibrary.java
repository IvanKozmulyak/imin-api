package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.ReferenceImageSet;
import com.imin.iminapi.model.StyleReferenceAnalysis;
import com.imin.iminapi.repository.StyleReferenceAnalysisRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ReferenceImageLibrary {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageLibrary.class);

    private final ResourceLoader resourceLoader;
    private final String configFile;
    private final StyleReferenceAnalysisRepository analysisRepo;
    private final ReferenceImageAnalyzer analyzer;
    private final Map<String, String> descriptors = new HashMap<>();
    private Map<String, List<LoadedReference>> byTag = Collections.emptyMap();

    public ReferenceImageLibrary(
            ResourceLoader resourceLoader,
            StyleReferenceAnalysisRepository analysisRepo,
            ReferenceImageAnalyzer analyzer,
            @Value("${poster.references.config-file:classpath:poster-references.yaml}") String configFile) {
        this.resourceLoader = resourceLoader;
        this.analysisRepo = analysisRepo;
        this.analyzer = analyzer;
        this.configFile = configFile;
    }

    @PostConstruct
    void load() {
        Resource resource = resourceLoader.getResource(configFile);
        if (!resource.exists()) {
            log.warn("Reference image config not found at {} — library will be empty", configFile);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;
            Object raw = root.get("references");
            if (!(raw instanceof Map<?, ?> refs)) return;
            byTag = resolveAll(refs);
            int totalLoaded = byTag.values().stream().mapToInt(List::size).sum();
            int nonEmptyTags = (int) byTag.values().stream().filter(l -> !l.isEmpty()).count();
            log.info("ReferenceImageLibrary loaded: {} tags, {} populated, {} total references",
                    byTag.size(), nonEmptyTags, totalLoaded);
        } catch (IOException e) {
            log.error("Failed to load reference image config {}", configFile, e);
        }
        loadDescriptors();
    }

    private Map<String, List<LoadedReference>> resolveAll(Map<?, ?> src) {
        Map<String, List<LoadedReference>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : src.entrySet()) {
            String tag = String.valueOf(e.getKey());
            List<LoadedReference> resolved = new java.util.ArrayList<>();
            if (e.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) continue;
                    try {
                        resolved.add(resolveOne(item.toString()));
                    } catch (IOException ioe) {
                        log.warn("Failed to load reference '{}' for tag '{}': {}", item, tag, ioe.getMessage());
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

    public String descriptor(String subStyleTag) {
        return descriptors.getOrDefault(subStyleTag, "");
    }

    /** Test hook — re-runs the cache check pass without reloading the YAML. */
    public void reloadDescriptors() {
        loadDescriptors();
    }

    /** Test hook — exposes the signature this library computes for a tag right now. */
    public String computeCurrentSignatureFor(String subStyleTag) {
        return imageSignature(toSignatureInputs(subStyleTag));
    }

    private void loadDescriptors() {
        descriptors.clear();
        String currentModel = analyzer.modelId();

        for (String tag : byTag.keySet()) {
            try {
                String value = resolveDescriptor(tag, currentModel);
                if (!value.isEmpty()) {
                    descriptors.put(tag, value);
                }
            } catch (Exception e) {
                log.warn("Descriptor task failed for tag '{}': {}", tag, e.getMessage());
            }
        }
        log.info("Style descriptors loaded for {} of {} tags", descriptors.size(), byTag.size());
    }

    private String resolveDescriptor(String tag, String currentModel) {
        try {
            String signature = imageSignature(toSignatureInputs(tag));
            Optional<StyleReferenceAnalysis> existing = analysisRepo.findById(tag);
            if (existing.isPresent()
                    && signature.equals(existing.get().getImageSignature())
                    && currentModel.equals(existing.get().getModelId())) {
                return existing.get().getDescriptor();
            }

            ReferenceImageSet refs = forTag(tag);
            String descriptor = analyzer.analyze(tag, refs.referenceUrls());
            if (descriptor == null || descriptor.isBlank()) {
                log.warn("Empty descriptor for tag '{}' — not persisting", tag);
                return "";
            }

            StyleReferenceAnalysis row = existing.orElseGet(StyleReferenceAnalysis::new);
            row.setSubStyleTag(tag);
            row.setDescriptor(descriptor);
            row.setImageSignature(signature);
            row.setModelId(currentModel);
            row.setAnalyzedAt(LocalDateTime.now());
            analysisRepo.save(row);
            return descriptor;
        } catch (RuntimeException e) {
            log.warn("Failed to analyze references for tag '{}': {}", tag, e.getMessage());
            return "";
        }
    }

    private List<SignatureInput> toSignatureInputs(String tag) {
        List<LoadedReference> refs = byTag.getOrDefault(tag, List.of());
        List<SignatureInput> inputs = new java.util.ArrayList<>(refs.size());
        for (int i = 0; i < refs.size(); i++) {
            try {
                inputs.add(new SignatureInput(refs.get(i).id(), bytesFor(refs.get(i))));
            } catch (Exception e) {
                log.warn("Could not read bytes for {}/{}: {}", tag, i, e.getMessage());
            }
        }
        return inputs;
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

    public record SignatureInput(String referenceId, byte[] bytes) {}

    static String imageSignature(List<SignatureInput> inputs) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            HexFormat hex = HexFormat.of();
            List<String> entries = inputs.stream()
                    .map(in -> in.referenceId() + ":" + hex.formatHex(sha.digest(in.bytes())))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            String joined = String.join("\n", entries);
            return hex.formatHex(sha.digest(joined.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record LoadedReference(String id, String urlOrDataUri, String sourceLocator) {}
}
