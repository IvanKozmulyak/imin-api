package com.imin.iminapi.service.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure static utility for measuring how textually similar a set of poster prompts are, so a
 * generation run can reject (or re-roll) variants whose prompts collapse into near-duplicates.
 *
 * <p>Similarity is the <strong>word-trigram Jaccard</strong> of two prompts. A prompt is normalized
 * (lowercased, punctuation flattened to spaces, whitespace collapsed) and split into words; the
 * trigram set is the distinct sequences of 3 consecutive words joined by a single space. Jaccard is
 * {@code |A ∩ B| / |A ∪ B|} over those sets.
 *
 * <p><strong>Short-text guard:</strong> a prompt with fewer than 3 words yields an <em>empty</em>
 * trigram set, so its similarity to anything (including an identical short prompt) is {@code 0.0}.
 * This utility is meant for long poster prompts; trivially short inputs are intentionally treated as
 * "not similar" rather than throwing.
 */
public final class PromptDistinctness {

    private PromptDistinctness() {
    }

    /**
     * Word-trigram Jaccard similarity of two prompts, in {@code [0.0, 1.0]}.
     *
     * <p>Returns {@code 0.0} when either prompt has fewer than 3 words (empty trigram set), and
     * {@code 0.0} when the union is empty. Identical long prompts return {@code 1.0}.
     */
    public static double trigramJaccard(String a, String b) {
        Set<String> ta = trigrams(a);
        Set<String> tb = trigrams(b);
        if (ta.isEmpty() && tb.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        if (union.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        return (double) intersection.size() / (double) union.size();
    }

    /**
     * The largest {@link #trigramJaccard} over every unordered pair of prompts. Returns {@code 0.0}
     * for fewer than 2 prompts.
     */
    public static double maxPairwiseSimilarity(List<String> prompts) {
        if (prompts == null || prompts.size() < 2) {
            return 0.0;
        }
        double max = 0.0;
        for (int i = 0; i < prompts.size(); i++) {
            for (int j = i + 1; j < prompts.size(); j++) {
                double sim = trigramJaccard(prompts.get(i), prompts.get(j));
                if (sim > max) {
                    max = sim;
                }
            }
        }
        return max;
    }

    /**
     * {@code true} iff every unordered pair of prompts has {@link #trigramJaccard} strictly below
     * {@code threshold}. Vacuously {@code true} for fewer than 2 prompts.
     */
    public static boolean allPairwiseBelow(List<String> prompts, double threshold) {
        if (prompts == null || prompts.size() < 2) {
            return true;
        }
        for (int i = 0; i < prompts.size(); i++) {
            for (int j = i + 1; j < prompts.size(); j++) {
                if (trigramJaccard(prompts.get(i), prompts.get(j)) >= threshold) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The distinct word-trigram set of a prompt; empty when the prompt has fewer than 3 words. */
    private static Set<String> trigrams(String text) {
        String[] words = words(text);
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 < words.length; i++) {
            out.add(words[i] + " " + words[i + 1] + " " + words[i + 2]);
        }
        return out;
    }

    /** Normalize and split: lowercase, punctuation → space, collapse whitespace, split on space. */
    private static String[] words(String text) {
        if (text == null) {
            return new String[0];
        }
        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("\\s+");
    }
}
