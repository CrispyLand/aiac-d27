package com.crispyland.rag;

import com.crispyland.rag.CitationEvaluator.EvalResults;
import com.crispyland.rag.CitationEvaluator.Question;
import com.crispyland.rag.CitationEvaluator.QuestionResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML report for Day 24 citation evaluation.
 * Shows citation quality metrics alongside answer correctness.
 */
public final class CitationReportGenerator {

    private CitationReportGenerator() {}

    public static String generate(EvalResults eval) {
        List<QuestionResult> results = eval.results();
        List<String> modeNames = eval.modeNames();
        List<Question> questions = eval.questions();

        Map<String, List<QuestionResult>> byMode = results.stream()
                .collect(Collectors.groupingBy(QuestionResult::mode, LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <title>Day 24 — Citation Evaluation</title>
                <style>
                """);
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");

        html.append("<div class=\"container\">\n");
        html.append("<h1>Day 24 — Quotes, Sources &amp; Anti-Hallucination</h1>\n");
        html.append("<p class=\"timestamp\">Generated: ").append(Instant.now()).append("</p>\n");

        appendSummaryTable(html, byMode, modeNames);
        appendAnalysis(html, byMode, modeNames, questions, results);
        appendMatrix(html, results, modeNames, questions);
        appendDetails(html, results, modeNames, questions);

        html.append("</div>\n");
        html.append("</body>\n</html>");
        return html.toString();
    }

    private static void appendStyles(StringBuilder html) {
        html.append("""
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: #0f172a; color: #e2e8f0; line-height: 1.6; padding: 2rem;
                }
                .container { max-width: 1300px; margin: 0 auto; }
                h1 { font-size: 2rem; margin-bottom: 0.25rem; color: #f8fafc; }
                h2 { font-size: 1.4rem; margin: 2rem 0 1rem; color: #94a3b8;
                     border-bottom: 1px solid #334155; padding-bottom: 0.5rem; }
                h3 { font-size: 1.1rem; margin: 1rem 0 0.5rem; color: #cbd5e1; }
                .timestamp { color: #64748b; font-size: 0.85rem; margin-bottom: 2rem; }

                /* Summary table */
                table.summary {
                    width: 100%; border-collapse: collapse; background: #1e293b;
                    border-radius: 12px; overflow: hidden; border: 1px solid #334155;
                    margin-bottom: 2rem;
                }
                table.summary th, table.summary td {
                    padding: 0.65rem 0.8rem; text-align: center; font-size: 0.85rem;
                    border-bottom: 1px solid #293548;
                }
                table.summary th {
                    background: #0f172a; color: #94a3b8; font-weight: 600;
                    text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.04em;
                }
                table.summary td:first-child { text-align: left; font-weight: 500; color: #e2e8f0; }
                table.summary tr:last-child td { border-bottom: none; }
                .num-correct { color: #22c55e; font-weight: 700; }
                .num-partial { color: #eab308; font-weight: 700; }
                .num-wrong { color: #ef4444; font-weight: 700; }
                .num-hallu { color: #f97316; font-weight: 700; }

                /* Analysis */
                .analysis {
                    background: #1e293b; border-radius: 12px; padding: 1.5rem;
                    border: 1px solid #334155; margin-bottom: 2rem;
                }
                .analysis p { margin-bottom: 0.75rem; font-size: 0.9rem; color: #cbd5e1; line-height: 1.7; }
                .analysis strong { color: #f8fafc; }
                .analysis ul { margin: 0.5rem 0 1rem 1.5rem; font-size: 0.85rem; color: #94a3b8; }
                .analysis li { margin-bottom: 0.3rem; }
                .hi { color: #22c55e; font-weight: 600; }
                .warn { color: #eab308; font-weight: 600; }

                /* Matrix */
                .matrix-wrap { overflow-x: auto; margin-bottom: 2rem; }
                table.matrix {
                    width: 100%; border-collapse: collapse; background: #1e293b;
                    border-radius: 12px; overflow: hidden; border: 1px solid #334155;
                }
                table.matrix th, table.matrix td {
                    padding: 0.5rem 0.6rem; text-align: center; font-size: 0.8rem;
                    border-bottom: 1px solid #1e293b;
                }
                table.matrix th {
                    background: #0f172a; color: #94a3b8; font-weight: 600;
                    text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.04em;
                }
                table.matrix td.q-label {
                    text-align: left; color: #cbd5e1; font-weight: 500;
                    max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
                }
                table.matrix td.cat { font-size: 0.7rem; color: #64748b; }
                .badge {
                    display: inline-block; padding: 0.15rem 0.5rem; border-radius: 6px;
                    font-size: 0.7rem; font-weight: 600; min-width: 54px;
                }
                .badge-correct { background: #166534; color: #bbf7d0; }
                .badge-partial { background: #854d0e; color: #fef08a; }
                .badge-wrong { background: #991b1b; color: #fecaca; }
                .badge-hallucinated { background: #9a3412; color: #fed7aa; }
                .badge-error { background: #44403c; color: #d6d3d1; }
                .badge-insufficient { background: #92400e; color: #fde68a; }
                .tick { color: #22c55e; font-weight: 700; }
                .cross { color: #ef4444; font-weight: 700; }
                .qv { font-size: 0.7rem; }
                .qv .verified { color: #22c55e; }
                .qv .rejected { color: #ef4444; }
                .qv .unverified { color: #eab308; }

                /* Details */
                details {
                    background: #1e293b; border-radius: 12px; margin-bottom: 0.75rem;
                    border: 1px solid #334155;
                }
                details[open] { border-color: #475569; }
                summary {
                    padding: 0.8rem 1rem; cursor: pointer; list-style: none;
                    display: flex; justify-content: space-between; align-items: center;
                    font-weight: 500; color: #e2e8f0;
                }
                summary::-webkit-details-marker { display: none; }
                summary::after {
                    content: '\\25B6'; font-size: 0.7rem; color: #64748b;
                    transition: transform 0.2s;
                }
                details[open] summary::after { transform: rotate(90deg); }
                .detail-body { padding: 0 1rem 1rem; }
                .detail-expected {
                    background: #0f172a; padding: 0.6rem 0.8rem; border-radius: 8px;
                    font-size: 0.8rem; margin-bottom: 0.75rem; color: #94a3b8;
                }
                .mode-block {
                    border: 1px solid #334155; border-radius: 8px; padding: 0.75rem;
                    margin: 0.5rem 0; background: #162032;
                }
                .mode-block-header {
                    display: flex; gap: 0.5rem; align-items: center; margin-bottom: 0.5rem;
                    flex-wrap: wrap;
                }
                .mode-name { font-size: 0.8rem; color: #94a3b8; font-weight: 600; min-width: 130px; }
                .mode-block .answer-text {
                    font-size: 0.8rem; color: #cbd5e1; margin: 0.5rem 0;
                    white-space: pre-wrap; line-height: 1.5;
                }
                .mode-block .cite-marker {
                    background: #1e3a5f; color: #93c5fd; font-size: 0.7rem;
                    padding: 0 4px; border-radius: 3px; font-weight: 700;
                }
                .mode-block .src-list {
                    list-style: none; padding: 0; margin: 0.25rem 0; font-size: 0.75rem;
                }
                .mode-block .src-list li { color: #64748b; padding: 2px 0; }
                .mode-block .src-list .src-file { color: #93c5fd; font-weight: 600; }
                .mode-block .src-list .src-section { color: #64748b; }
                .mode-block .src-list .src-chunk { color: #475569; font-size: 0.65rem; }
                .mode-block .quote-block {
                    border-left: 3px solid #6366f1; background: #1e1b4b; padding: 6px 10px;
                    margin: 4px 0; border-radius: 0 6px 6px 0; font-size: 0.75rem;
                    color: #a5b4fc; font-style: italic;
                }
                .mode-block .quote-src { font-style: normal; color: #64748b; margin-left: 6px; }
                .quote-status {
                    display: inline-block; font-size: 0.6rem; font-weight: 700;
                    padding: 1px 5px; border-radius: 3px; margin-left: 5px;
                    font-style: normal; vertical-align: middle;
                }
                .quote-status.verified { background: #166534; color: #bbf7d0; }
                .quote-status.rejected { background: #991b1b; color: #fecaca; }
                .quote-status.unverified { background: #854d0e; color: #fef08a; }
                .insufficient-banner {
                    background: #422006; border: 1px solid #92400e; border-radius: 6px;
                    padding: 8px 12px; color: #fde68a; font-size: 0.8rem; margin: 0.5rem 0;
                }

                @media print {
                    body { background: white; color: #1a1a1a; padding: 1rem; }
                    .analysis, table.summary, table.matrix, details, .mode-block {
                        background: white; border-color: #ddd; color: #1a1a1a;
                    }
                }
                """);
    }

    private static void appendSummaryTable(StringBuilder html,
                                           Map<String, List<QuestionResult>> byMode,
                                           List<String> modeNames) {
        html.append("<h2>Summary</h2>\n");
        html.append("<div class=\"matrix-wrap\">\n<table class=\"summary\">\n<thead><tr>\n");
        html.append("  <th>Mode</th><th>Correct</th><th>Partial</th><th>Wrong</th><th>Hallu.</th>")
            .append("<th>Has Sources</th><th>Has Quotes</th>")
            .append("<th>Quotes Verified</th><th>Quotes Rejected</th>")
            .append("<th>Avg ms</th><th>Total Tokens</th>\n");
        html.append("</tr></thead>\n<tbody>\n");

        for (String mode : modeNames) {
            List<QuestionResult> mr = byMode.get(mode);
            long correct = mr.stream().filter(r -> "correct".equals(r.answerScore())).count();
            long partial = mr.stream().filter(r -> "partial".equals(r.answerScore())).count();
            long wrong = mr.stream().filter(r -> "wrong".equals(r.answerScore())).count();
            long hallu = mr.stream().filter(r -> "hallucinated".equals(r.answerScore())).count();
            long withSources = mr.stream().filter(QuestionResult::hasSources).count();
            long withQuotes = mr.stream().filter(QuestionResult::hasQuotes).count();
            int totalVerified = mr.stream().mapToInt(QuestionResult::quotesVerified).sum();
            int totalRejected = mr.stream().mapToInt(QuestionResult::quotesRejected).sum();
            long avgMs = mr.stream().mapToLong(r -> r.ragLatencyMs() + r.answerLatencyMs()).sum()
                    / Math.max(mr.size(), 1);
            long totalTokens = mr.stream()
                    .mapToLong(r -> r.ragUsage().totalTokens() + r.answerUsage().totalTokens())
                    .sum();

            html.append("<tr>\n");
            html.append("  <td>").append(esc(mode)).append("</td>\n");
            html.append("  <td class=\"num-correct\">").append(correct).append("</td>\n");
            html.append("  <td class=\"num-partial\">").append(partial).append("</td>\n");
            html.append("  <td class=\"num-wrong\">").append(wrong).append("</td>\n");
            html.append("  <td class=\"num-hallu\">").append(hallu).append("</td>\n");
            html.append("  <td>").append(withSources).append("/").append(mr.size()).append("</td>\n");
            html.append("  <td>").append(withQuotes).append("/").append(mr.size()).append("</td>\n");
            html.append("  <td class=\"num-correct\">").append(totalVerified).append("</td>\n");
            html.append("  <td class=\"num-wrong\">").append(totalRejected).append("</td>\n");
            html.append("  <td>").append(avgMs).append("</td>\n");
            html.append("  <td>").append(String.format("%,d", totalTokens)).append("</td>\n");
            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n</div>\n");
    }

    private static void appendAnalysis(StringBuilder html,
                                       Map<String, List<QuestionResult>> byMode,
                                       List<String> modeNames, List<Question> questions,
                                       List<QuestionResult> allResults) {
        html.append("<h2>Analysis</h2>\n");
        html.append("<div class=\"analysis\">\n");

        html.append("<p><strong>What each mode does:</strong></p>\n<ul>\n");
        html.append("<li><strong>standard</strong> — sim+rerank with normal thresholds (similarity ≥ 0.30, rerank score ≥ 5). The model is prompted to return structured answers with sources and quotes.</li>\n");
        html.append("<li><strong>anti-hallucination</strong> — same pipeline but with tighter thresholds (similarity ≥ 0.45, rerank score ≥ 7). Weak matches are dropped, forcing the model to say \"I don't know\" when context is insufficient.</li>\n");
        html.append("</ul>\n");

        html.append("<p><strong>Key findings:</strong></p>\n<ul>\n");

        for (String mode : modeNames) {
            List<QuestionResult> mr = byMode.get(mode);
            long correct = mr.stream().filter(r -> "correct".equals(r.answerScore())).count();
            long withSources = mr.stream().filter(QuestionResult::hasSources).count();
            long withQuotes = mr.stream().filter(QuestionResult::hasQuotes).count();
            int verified = mr.stream().mapToInt(QuestionResult::quotesVerified).sum();
            int total = mr.stream().mapToInt(QuestionResult::quotesTotal).sum();
            long insufficient = mr.stream().filter(r -> r.parsedAnswer().insufficientContext()).count();

            html.append("<li><strong>").append(esc(mode)).append("</strong>: ")
                .append("<span class=\"hi\">").append(correct).append("/").append(mr.size())
                .append(" correct</span>, sources in ").append(withSources).append("/").append(mr.size())
                .append(" answers, quotes in ").append(withQuotes).append("/").append(mr.size())
                .append(" answers");
            if (total > 0) {
                html.append(", <span class=\"hi\">").append(verified).append("/").append(total)
                    .append(" quotes verified</span>");
            }
            if (insufficient > 0) {
                html.append(", ").append(insufficient).append(" \"I don't know\" responses");
            }
            html.append(".</li>\n");
        }

        // Compare modes
        if (modeNames.size() >= 2) {
            List<QuestionResult> std = byMode.get(modeNames.get(0));
            List<QuestionResult> ah = byMode.get(modeNames.get(1));
            long stdHallu = std.stream().filter(r -> "hallucinated".equals(r.answerScore())).count();
            long ahHallu = ah.stream().filter(r -> "hallucinated".equals(r.answerScore())).count();
            if (ahHallu < stdHallu) {
                html.append("<li>Anti-hallucination mode <span class=\"hi\">reduced hallucinations</span> from ")
                    .append(stdHallu).append(" to ").append(ahHallu).append(".</li>\n");
            } else if (ahHallu == 0 && stdHallu == 0) {
                html.append("<li>Zero hallucinations in both modes.</li>\n");
            }
        }

        html.append("</ul>\n</div>\n");
    }

    private static void appendMatrix(StringBuilder html, List<QuestionResult> results,
                                     List<String> modeNames, List<Question> questions) {
        html.append("<h2>Question x Mode Matrix</h2>\n");
        html.append("<div class=\"matrix-wrap\">\n<table class=\"matrix\">\n<thead><tr>\n");
        html.append("  <th>#</th><th>Question</th><th>Cat.</th>\n");
        for (String m : modeNames) {
            html.append("  <th colspan=\"4\">").append(esc(m)).append("</th>\n");
        }
        html.append("</tr>\n<tr>\n  <th></th><th></th><th></th>\n");
        for (int i = 0; i < modeNames.size(); i++) {
            html.append("  <th>Score</th><th>Src</th><th>Qte</th><th>Verified</th>\n");
        }
        html.append("</tr></thead>\n<tbody>\n");

        Map<Integer, Map<String, QuestionResult>> indexed = new LinkedHashMap<>();
        for (QuestionResult r : results) {
            indexed.computeIfAbsent(r.number(), k -> new LinkedHashMap<>()).put(r.mode(), r);
        }

        for (Question q : questions) {
            Map<String, QuestionResult> row = indexed.getOrDefault(q.number(), Map.of());
            html.append("<tr>\n");
            html.append("  <td>").append(q.number()).append("</td>\n");
            html.append("  <td class=\"q-label\" title=\"").append(escAttr(q.text())).append("\">")
                .append(esc(q.text())).append("</td>\n");
            html.append("  <td class=\"cat\">").append(esc(q.category())).append("</td>\n");
            for (String m : modeNames) {
                QuestionResult r = row.get(m);
                if (r == null) {
                    html.append("  <td>-</td><td>-</td><td>-</td><td>-</td>\n");
                } else {
                    html.append("  <td><span class=\"badge badge-").append(r.answerScore()).append("\">")
                        .append(r.answerScore()).append("</span>");
                    if (r.parsedAnswer().insufficientContext()) {
                        html.append(" <span class=\"badge badge-insufficient\">IDK</span>");
                    }
                    html.append("</td>\n");
                    html.append("  <td>").append(r.hasSources() ? "<span class=\"tick\">&#10003;</span>" : "<span class=\"cross\">&#10007;</span>").append("</td>\n");
                    html.append("  <td>").append(r.hasQuotes() ? "<span class=\"tick\">&#10003;</span>" : "<span class=\"cross\">&#10007;</span>").append("</td>\n");
                    html.append("  <td class=\"qv\">");
                    if (r.quotesTotal() > 0) {
                        html.append("<span class=\"verified\">").append(r.quotesVerified()).append("V</span>");
                        if (r.quotesRejected() > 0) {
                            html.append(" <span class=\"rejected\">").append(r.quotesRejected()).append("R</span>");
                        }
                    } else {
                        html.append("-");
                    }
                    html.append("</td>\n");
                }
            }
            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n</div>\n");
    }

    private static void appendDetails(StringBuilder html, List<QuestionResult> results,
                                      List<String> modeNames, List<Question> questions) {
        html.append("<h2>Per-Question Detail</h2>\n");

        Map<Integer, Map<String, QuestionResult>> indexed = new LinkedHashMap<>();
        for (QuestionResult r : results) {
            indexed.computeIfAbsent(r.number(), k -> new LinkedHashMap<>()).put(r.mode(), r);
        }

        for (Question q : questions) {
            Map<String, QuestionResult> row = indexed.getOrDefault(q.number(), Map.of());
            html.append("<details>\n<summary>Q").append(q.number()).append(": ")
                .append(esc(q.text())).append("</summary>\n");
            html.append("<div class=\"detail-body\">\n");
            html.append("  <div class=\"detail-expected\"><strong>Expected:</strong> ")
                .append(esc(q.expectedAnswer()))
                .append("<br/><strong>Expected sources:</strong> ")
                .append(esc(q.expectedSources().isBlank() ? "(none)" : q.expectedSources()))
                .append("<br/><strong>Category:</strong> ").append(esc(q.category()))
                .append("</div>\n");

            for (String m : modeNames) {
                QuestionResult r = row.get(m);
                if (r == null) continue;
                RagAnswer parsed = r.parsedAnswer();

                html.append("  <div class=\"mode-block\">\n");
                html.append("    <div class=\"mode-block-header\">\n");
                html.append("      <span class=\"mode-name\">").append(esc(m)).append("</span>\n");
                html.append("      <span class=\"badge badge-").append(r.answerScore()).append("\">")
                    .append(r.answerScore()).append("</span>\n");
                html.append("      ").append(r.hasSources() ? "<span class=\"tick\">&#10003; sources</span>" : "<span class=\"cross\">&#10007; sources</span>").append("\n");
                html.append("      ").append(r.hasQuotes() ? "<span class=\"tick\">&#10003; quotes</span>" : "<span class=\"cross\">&#10007; quotes</span>").append("\n");
                if (r.quotesTotal() > 0) {
                    html.append("      <span class=\"qv\">(");
                    html.append("<span class=\"verified\">").append(r.quotesVerified()).append(" verified</span>");
                    if (r.quotesRejected() > 0) {
                        html.append(" · <span class=\"rejected\">").append(r.quotesRejected()).append(" rejected</span>");
                    }
                    html.append(")</span>\n");
                }
                if (r.parsedAnswer().insufficientContext()) {
                    html.append("      <span class=\"badge badge-insufficient\">INSUFFICIENT CONTEXT</span>\n");
                }
                html.append("    </div>\n");

                // Answer text
                if (parsed.insufficientContext()) {
                    html.append("    <div class=\"insufficient-banner\">").append(esc(parsed.answer())).append("</div>\n");
                } else {
                    // Answer with citation markers highlighted
                    String ansHtml = esc(parsed.answer()).replaceAll("\\[(\\d+)]", "<span class=\"cite-marker\">[$1]</span>");
                    html.append("    <div class=\"answer-text\">").append(ansHtml).append("</div>\n");
                }

                // Sources
                if (!parsed.sources().isEmpty()) {
                    html.append("    <ul class=\"src-list\">\n");
                    for (int i = 0; i < parsed.sources().size(); i++) {
                        RagAnswer.Source src = parsed.sources().get(i);
                        html.append("      <li><span class=\"cite-marker\">[").append(i + 1).append("]</span> ");
                        html.append("<span class=\"src-file\">").append(esc(src.file())).append("</span>");
                        if (!src.section().isEmpty()) {
                            html.append(" <span class=\"src-section\">| ").append(esc(src.section())).append("</span>");
                        }
                        if (!src.chunkId().isEmpty()) {
                            html.append(" <span class=\"src-chunk\">(").append(esc(src.chunkId())).append(")</span>");
                        }
                        html.append("</li>\n");
                    }
                    html.append("    </ul>\n");
                }

                // Quotes
                if (!parsed.quotes().isEmpty()) {
                    for (RagAnswer.Quote quote : parsed.quotes()) {
                        html.append("    <div class=\"quote-block\">&ldquo;").append(esc(quote.text())).append("&rdquo;");
                        if (!quote.sourceRef().isEmpty()) {
                            html.append("<span class=\"quote-src\"> &mdash; ").append(esc(quote.sourceRef())).append("</span>");
                        }
                        String statusClass = switch (quote.status()) {
                            case VERIFIED -> "verified";
                            case REJECTED -> "rejected";
                            case UNVERIFIED -> "unverified";
                        };
                        String statusLabel = switch (quote.status()) {
                            case VERIFIED -> "VERIFIED";
                            case REJECTED -> "REJECTED";
                            case UNVERIFIED -> "UNVERIFIED";
                        };
                        html.append("<span class=\"quote-status ").append(statusClass).append("\">")
                            .append(statusLabel).append("</span>");
                        html.append("</div>\n");
                    }
                }

                // Judge reason
                if (r.judgeReason() != null && !r.judgeReason().isBlank()) {
                    html.append("    <div style=\"font-size:0.7rem;color:#64748b;margin-top:0.5rem\"><strong>Judge:</strong> ")
                        .append(esc(r.judgeReason())).append("</div>\n");
                }

                html.append("  </div>\n");
            }
            html.append("</div>\n</details>\n");
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("'", "&#39;");
    }
}
