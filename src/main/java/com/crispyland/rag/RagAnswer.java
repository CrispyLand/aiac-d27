package com.crispyland.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a structured RAG response into its constituent parts: answer text,
 * cited sources, and supporting quotes. The expected format uses markdown
 * headings ({@code ### Answer}, {@code ### Sources}, {@code ### Quotes}).
 * <p>
 * If the model does not produce the expected structure, parsing degrades
 * gracefully: the full text becomes the answer, and sources/quotes are empty.
 */
public record RagAnswer(
        String fullText,
        String answer,
        List<Source> sources,
        List<Quote> quotes,
        boolean insufficientContext
) {

    public record Source(String file, String section, String chunkId) {
        @Override public String toString() {
            return file + " | " + section + " (" + chunkId + ")";
        }
    }

    public record Quote(String text, String sourceRef, QuoteStatus status) {}

    public enum QuoteStatus { VERIFIED, REJECTED, UNVERIFIED }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    public boolean hasQuotes() {
        return !quotes.isEmpty();
    }

    /**
     * Parses a model response that follows the structured format.
     * If the headings are not found, the whole text is treated as the answer.
     */
    public static RagAnswer parse(String text, List<ScoredChunk> providedChunks) {
        if (text == null || text.isBlank()) {
            return new RagAnswer("", "", List.of(), List.of(), false);
        }

        String answerSection = extractSection(text, "Answer");
        String sourcesSection = extractSection(text, "Sources");
        String quotesSection = extractSection(text, "Quotes");

        // If no structured headings found, the whole text is the answer
        if (answerSection == null && sourcesSection == null && quotesSection == null) {
            boolean insufficient = isInsufficientContext(text);
            return new RagAnswer(text, text.strip(), List.of(), List.of(), insufficient);
        }

        String answer = answerSection != null ? answerSection.strip() : text.strip();
        boolean insufficient = isInsufficientContext(answer);

        List<Source> sources = parseSources(sourcesSection);
        List<Quote> rawQuotes = parseQuotes(quotesSection);

        // Verify quotes against provided chunks
        List<Quote> verifiedQuotes = verifyQuotes(rawQuotes, providedChunks);

        return new RagAnswer(text, answer, sources, verifiedQuotes, insufficient);
    }

    /**
     * Extracts the text between a {@code ### heading} and the next {@code ###} or end-of-string.
     */
    private static String extractSection(String text, String heading) {
        // Match ### heading (case-insensitive, allow whitespace variations)
        Pattern p = Pattern.compile(
                "###\\s+" + Pattern.quote(heading) + "\\s*\\n(.*?)(?=###\\s|$)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * Parses source lines. Expected formats:
     * <pre>
     *   - FileName.java | section name (chunk-id)
     *   - FileName.java | section name
     *   [1] FileName.java | section name (chunk-id)
     * </pre>
     */
    private static List<Source> parseSources(String section) {
        if (section == null || section.isBlank() || section.contains("(none)")) {
            return List.of();
        }
        List<Source> sources = new ArrayList<>();
        for (String line : section.split("\\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            // Strip leading bullet / number marker
            line = line.replaceFirst("^[-*•]\\s*", "");
            line = line.replaceFirst("^\\[\\d+]\\s*", "");
            if (line.isBlank()) continue;

            // Try to parse: file | section (chunkId)
            String file = line, sec = "", chunkId = "";
            int pipeIdx = line.indexOf('|');
            if (pipeIdx >= 0) {
                file = line.substring(0, pipeIdx).strip();
                String rest = line.substring(pipeIdx + 1).strip();
                // Extract chunkId from parentheses at the end
                Matcher cid = Pattern.compile("\\(([^)]+)\\)\\s*$").matcher(rest);
                if (cid.find()) {
                    chunkId = cid.group(1);
                    sec = rest.substring(0, cid.start()).strip();
                } else {
                    sec = rest;
                }
            }
            sources.add(new Source(file, sec, chunkId));
        }
        return sources;
    }

    /**
     * Parses quote lines. Expected formats:
     * <pre>
     *   > "exact quote text" — FileName.java
     *   > "exact quote text"
     *   [1] "exact quote text" — FileName.java
     * </pre>
     */
    private static List<Quote> parseQuotes(String section) {
        if (section == null || section.isBlank() || section.contains("(none)")) {
            return List.of();
        }
        List<Quote> quotes = new ArrayList<>();
        // Match quoted text with optional source reference
        Pattern p = Pattern.compile(
                "(?:>|\\[\\d+]|[-*•])\\s*\"([^\"]+)\"(?:\\s*[—–-]\\s*(.+))?",
                Pattern.MULTILINE);
        Matcher m = p.matcher(section);
        while (m.find()) {
            String quoteText = m.group(1).strip();
            String sourceRef = m.group(2) != null ? m.group(2).strip() : "";
            quotes.add(new Quote(quoteText, sourceRef, QuoteStatus.UNVERIFIED));
        }

        // Fallback: if no quoted strings found, try lines starting with > as plain quotes
        if (quotes.isEmpty()) {
            for (String line : section.split("\\n")) {
                line = line.strip();
                if (line.startsWith(">")) {
                    String q = line.substring(1).strip();
                    // Remove surrounding quotes if present
                    if (q.startsWith("\"") && q.endsWith("\"")) {
                        q = q.substring(1, q.length() - 1);
                    }
                    if (!q.isBlank()) {
                        quotes.add(new Quote(q, "", QuoteStatus.UNVERIFIED));
                    }
                }
            }
        }
        return quotes;
    }

    /**
     * Verifies each quote against the provided chunks. A quote is VERIFIED if
     * its text appears (normalized) as a substring of any chunk's text.
     * REJECTED if no chunk contains it. Stays UNVERIFIED if no chunks were provided.
     */
    static List<Quote> verifyQuotes(List<Quote> quotes, List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return quotes; // no context to verify against
        }
        // Build normalized context from all chunks
        List<String> normalizedChunks = chunks.stream()
                .map(sc -> normalize(sc.chunk().text()))
                .toList();

        return quotes.stream().map(q -> {
            String normQuote = normalize(q.text());
            if (normQuote.length() < 10) {
                // Too short to verify meaningfully
                return q;
            }
            boolean found = normalizedChunks.stream()
                    .anyMatch(nc -> nc.contains(normQuote) || fuzzyContains(nc, normQuote));
            return new Quote(q.text(), q.sourceRef(),
                    found ? QuoteStatus.VERIFIED : QuoteStatus.REJECTED);
        }).toList();
    }

    /**
     * Checks if the normalized chunk text contains most of the quote words
     * in sequence (allowing minor paraphrasing).
     */
    private static boolean fuzzyContains(String chunkNorm, String quoteNorm) {
        // Split quote into words and check if 80%+ appear in the chunk in order
        String[] words = quoteNorm.split("\\s+");
        if (words.length < 4) return false;

        int matched = 0;
        int searchFrom = 0;
        for (String w : words) {
            int idx = chunkNorm.indexOf(w, searchFrom);
            if (idx >= 0) {
                matched++;
                searchFrom = idx + w.length();
            }
        }
        return (double) matched / words.length >= 0.7;
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[\\s]+", " ")
                .replaceAll("[\u201c\u201d\u2018\u2019`]", "'")
                .replaceAll("[—–]", "-")
                .strip();
    }

    private static boolean isInsufficientContext(String text) {
        String lower = text.toLowerCase();
        return lower.contains("insufficient context") ||
                lower.contains("don't have enough information") ||
                lower.contains("do not have enough information") ||
                lower.contains("no relevant context") ||
                lower.contains("not enough information") ||
                lower.contains("cannot answer") ||
                lower.contains("can't answer") ||
                (lower.contains("no ") && lower.contains("found") && lower.contains("context"));
    }
}
