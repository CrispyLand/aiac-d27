package com.crispyland.rag;

import com.crispyland.rag.RagEvaluator.EvalResults;
import com.crispyland.rag.RagEvaluator.Question;
import com.crispyland.rag.RagEvaluator.QuestionResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML report from RAG evaluation results.
 * The output has no external dependencies — all CSS and JS are inline.
 */
public final class RagEvalReportGenerator {

    private RagEvalReportGenerator() {}

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
                <title>RAG Evaluation Report</title>
                <style>
                """);
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");

        html.append("<div class=\"container\">\n");
        html.append("<h1>RAG Evaluation Report</h1>\n");
        html.append("<p class=\"timestamp\">Generated: ").append(Instant.now()).append("</p>\n");

        // Summary cards
        appendSummaryCards(html, byMode, modeNames);

        // Bar chart
        appendBarChart(html, byMode, modeNames);

        // Question × Mode matrix
        appendMatrix(html, results, modeNames, questions);

        // Cost vs Quality
        appendCostQuality(html, byMode, modeNames);

        // Expandable per-question detail
        appendDetails(html, results, modeNames, questions);

        html.append("</div>\n");
        appendScript(html);
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
                .container { max-width: 1200px; margin: 0 auto; }
                h1 { font-size: 2rem; margin-bottom: 0.25rem; color: #f8fafc; }
                h2 { font-size: 1.4rem; margin: 2rem 0 1rem; color: #94a3b8; border-bottom: 1px solid #334155; padding-bottom: 0.5rem; }
                h3 { font-size: 1.1rem; margin: 1rem 0 0.5rem; color: #cbd5e1; }
                .timestamp { color: #64748b; font-size: 0.85rem; margin-bottom: 2rem; }

                /* Summary cards */
                .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
                .card {
                    background: #1e293b; border-radius: 12px; padding: 1.25rem;
                    border: 1px solid #334155; position: relative; overflow: hidden;
                }
                .card::before {
                    content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px;
                }
                .card.baseline::before { background: #64748b; }
                .card.similarity-only::before { background: #3b82f6; }
                .card.sim-rerank::before { background: #8b5cf6; }
                .card.sim-rerank-rewrite::before { background: #10b981; }
                .card-title { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.05em; color: #94a3b8; margin-bottom: 0.75rem; }
                .card-score { font-size: 2.2rem; font-weight: 700; color: #f8fafc; }
                .card-score small { font-size: 0.9rem; color: #64748b; font-weight: 400; }
                .card-stats { display: flex; gap: 1rem; margin-top: 0.75rem; font-size: 0.8rem; }
                .card-stat { display: flex; align-items: center; gap: 0.3rem; }
                .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
                .dot-correct { background: #22c55e; }
                .dot-partial { background: #eab308; }
                .dot-wrong { background: #ef4444; }
                .dot-hallucinated { background: #f97316; }

                /* Chart */
                .chart-container { background: #1e293b; border-radius: 12px; padding: 1.5rem; border: 1px solid #334155; margin-bottom: 2rem; }
                .chart-container svg { width: 100%; }
                .chart-legend { display: flex; gap: 1.5rem; margin-top: 1rem; justify-content: center; font-size: 0.8rem; }
                .legend-item { display: flex; align-items: center; gap: 0.4rem; }
                .legend-swatch { width: 14px; height: 14px; border-radius: 3px; }

                /* Matrix */
                .matrix-wrap { overflow-x: auto; margin-bottom: 2rem; }
                table.matrix {
                    width: 100%; border-collapse: collapse; background: #1e293b;
                    border-radius: 12px; overflow: hidden; border: 1px solid #334155;
                }
                table.matrix th, table.matrix td {
                    padding: 0.6rem 0.8rem; text-align: center; font-size: 0.85rem;
                    border-bottom: 1px solid #1e293b;
                }
                table.matrix th { background: #0f172a; color: #94a3b8; font-weight: 600; text-transform: uppercase; font-size: 0.75rem; letter-spacing: 0.04em; }
                table.matrix td.q-label { text-align: left; color: #cbd5e1; font-weight: 500; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                table.matrix td.cat { font-size: 0.75rem; color: #64748b; }
                .badge {
                    display: inline-block; padding: 0.2rem 0.6rem; border-radius: 6px;
                    font-size: 0.75rem; font-weight: 600; min-width: 60px;
                }
                .badge-correct { background: #166534; color: #bbf7d0; }
                .badge-partial { background: #854d0e; color: #fef08a; }
                .badge-wrong { background: #991b1b; color: #fecaca; }
                .badge-hallucinated { background: #9a3412; color: #fed7aa; }
                .badge-error { background: #44403c; color: #d6d3d1; }
                .src-found { color: #22c55e; }
                .src-missing { color: #ef4444; }

                /* Cost-quality */
                .cq-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
                .cq-card { background: #1e293b; border-radius: 12px; padding: 1.25rem; border: 1px solid #334155; }
                .cq-row { display: flex; justify-content: space-between; padding: 0.4rem 0; border-bottom: 1px solid #1e293b; font-size: 0.85rem; }
                .cq-label { color: #94a3b8; }
                .cq-value { color: #e2e8f0; font-weight: 600; font-variant-numeric: tabular-nums; }

                /* Details */
                details { background: #1e293b; border-radius: 12px; margin-bottom: 0.75rem; border: 1px solid #334155; }
                details[open] { border-color: #475569; }
                summary {
                    padding: 0.8rem 1rem; cursor: pointer; list-style: none;
                    display: flex; justify-content: space-between; align-items: center;
                    font-weight: 500; color: #e2e8f0;
                }
                summary::-webkit-details-marker { display: none; }
                summary::after { content: '\\25B6'; font-size: 0.7rem; color: #64748b; transition: transform 0.2s; }
                details[open] summary::after { transform: rotate(90deg); }
                .detail-body { padding: 0 1rem 1rem; }
                .detail-expected { background: #0f172a; padding: 0.6rem 0.8rem; border-radius: 8px; font-size: 0.8rem; margin-bottom: 0.75rem; color: #94a3b8; }
                .detail-answer { font-size: 0.85rem; color: #cbd5e1; margin-top: 0.5rem; line-height: 1.5; }
                .detail-mode-row { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 0.5rem; flex-wrap: wrap; }
                .detail-mode-name { font-size: 0.75rem; color: #64748b; min-width: 120px; }
                .detail-sources { font-size: 0.75rem; color: #64748b; margin-left: 0.5rem; }
                .detail-full-answer { margin-top: 0.5rem; padding: 0.5rem; background: #0f172a; border-radius: 6px; font-size: 0.8rem; white-space: pre-wrap; color: #94a3b8; max-height: 200px; overflow-y: auto; }

                /* Print */
                @media print {
                    body { background: white; color: #1a1a1a; padding: 1rem; }
                    .card, .chart-container, table.matrix, .cq-card, details {
                        background: white; border-color: #ddd; color: #1a1a1a;
                    }
                    table.matrix th { background: #f5f5f5; color: #333; }
                    .badge-correct { background: #d4edda; color: #155724; }
                    .badge-partial { background: #fff3cd; color: #856404; }
                    .badge-wrong { background: #f8d7da; color: #721c24; }
                    .badge-hallucinated { background: #ffe0cc; color: #7a3600; }
                    h1, h2, h3, .card-score { color: #1a1a1a; }
                    .timestamp, .card-title, .cq-label, .detail-mode-name { color: #666; }
                    details { break-inside: avoid; }
                }
                """);
    }

    private static void appendSummaryCards(StringBuilder html, Map<String, List<QuestionResult>> byMode, List<String> modeNames) {
        html.append("<h2>Mode Comparison</h2>\n");
        html.append("<div class=\"cards\">\n");

        for (String mode : modeNames) {
            List<QuestionResult> mr = byMode.get(mode);
            long correct = mr.stream().filter(r -> "correct".equals(r.answerScore())).count();
            long partial = mr.stream().filter(r -> "partial".equals(r.answerScore())).count();
            long wrong = mr.stream().filter(r -> "wrong".equals(r.answerScore())).count();
            long hallucinated = mr.stream().filter(r -> "hallucinated".equals(r.answerScore())).count();

            String cssClass = mode.replace("+", "-");
            html.append("<div class=\"card ").append(cssClass).append("\">\n");
            html.append("  <div class=\"card-title\">").append(escHtml(mode)).append("</div>\n");
            html.append("  <div class=\"card-score\">").append(correct).append("<small>/").append(mr.size()).append(" correct</small></div>\n");
            html.append("  <div class=\"card-stats\">\n");
            html.append("    <span class=\"card-stat\"><span class=\"dot dot-correct\"></span>").append(correct).append("</span>\n");
            html.append("    <span class=\"card-stat\"><span class=\"dot dot-partial\"></span>").append(partial).append("</span>\n");
            html.append("    <span class=\"card-stat\"><span class=\"dot dot-wrong\"></span>").append(wrong).append("</span>\n");
            html.append("    <span class=\"card-stat\"><span class=\"dot dot-hallucinated\"></span>").append(hallucinated).append("</span>\n");
            html.append("  </div>\n");
            html.append("</div>\n");
        }
        html.append("</div>\n");
    }

    private static void appendBarChart(StringBuilder html, Map<String, List<QuestionResult>> byMode, List<String> modeNames) {
        html.append("<h2>Score Distribution</h2>\n");
        html.append("<div class=\"chart-container\">\n");

        String[] categories = {"correct", "partial", "wrong", "hallucinated"};
        String[] colors = {"#22c55e", "#eab308", "#ef4444", "#f97316"};

        int modeCount = modeNames.size();
        int chartW = 800, chartH = 300;
        int barGroupW = chartW / (categories.length + 1);
        int barW = Math.max(12, (barGroupW - 20) / modeCount);
        int maxVal = 10; // 10 questions per mode

        html.append("<svg viewBox=\"0 0 ").append(chartW + 80).append(" ").append(chartH + 60).append("\">\n");
        // Y axis
        html.append("<g transform=\"translate(60,20)\">\n");
        for (int i = 0; i <= maxVal; i += 2) {
            int y = chartH - (i * chartH / maxVal);
            html.append("  <line x1=\"0\" y1=\"").append(y).append("\" x2=\"").append(chartW)
                    .append("\" y2=\"").append(y).append("\" stroke=\"#334155\" stroke-dasharray=\"4\"/>\n");
            html.append("  <text x=\"-8\" y=\"").append(y + 4).append("\" text-anchor=\"end\" fill=\"#64748b\" font-size=\"11\">").append(i).append("</text>\n");
        }

        for (int ci = 0; ci < categories.length; ci++) {
            int groupX = ci * barGroupW + barGroupW / 2;
            // Category label
            html.append("  <text x=\"").append(groupX + (modeCount * barW) / 2)
                    .append("\" y=\"").append(chartH + 20)
                    .append("\" text-anchor=\"middle\" fill=\"#94a3b8\" font-size=\"11\">")
                    .append(categories[ci]).append("</text>\n");

            for (int mi = 0; mi < modeCount; mi++) {
                String mode = modeNames.get(mi);
                List<QuestionResult> mr = byMode.get(mode);
                String cat = categories[ci];
                long count = mr.stream().filter(r -> cat.equals(r.answerScore())).count();
                int barH = (int)(count * chartH / maxVal);
                int x = groupX + mi * barW;
                int y = chartH - barH;
                String fill = modeBarColor(mi);
                html.append("  <rect x=\"").append(x).append("\" y=\"").append(y)
                        .append("\" width=\"").append(barW - 2).append("\" height=\"").append(barH)
                        .append("\" rx=\"3\" fill=\"").append(fill).append("\" opacity=\"0.85\"/>\n");
                if (count > 0) {
                    html.append("  <text x=\"").append(x + barW / 2 - 1).append("\" y=\"").append(y - 4)
                            .append("\" text-anchor=\"middle\" fill=\"").append(fill)
                            .append("\" font-size=\"10\" font-weight=\"600\">").append(count).append("</text>\n");
                }
            }
        }

        html.append("</g>\n</svg>\n");

        // Legend
        String[] modeColors = {"#64748b", "#3b82f6", "#8b5cf6", "#10b981"};
        html.append("<div class=\"chart-legend\">\n");
        for (int i = 0; i < modeNames.size(); i++) {
            html.append("  <span class=\"legend-item\"><span class=\"legend-swatch\" style=\"background:").append(modeColors[i]).append("\"></span>")
                    .append(escHtml(modeNames.get(i))).append("</span>\n");
        }
        html.append("</div>\n");
        html.append("</div>\n");
    }

    private static String modeBarColor(int idx) {
        return switch (idx) {
            case 0 -> "#64748b";
            case 1 -> "#3b82f6";
            case 2 -> "#8b5cf6";
            case 3 -> "#10b981";
            default -> "#94a3b8";
        };
    }

    private static void appendMatrix(StringBuilder html, List<QuestionResult> results,
                                     List<String> modeNames, List<Question> questions) {
        html.append("<h2>Question x Mode Matrix</h2>\n");
        html.append("<div class=\"matrix-wrap\">\n<table class=\"matrix\">\n<thead><tr>\n");
        html.append("  <th>#</th><th>Question</th><th>Category</th>\n");
        for (String m : modeNames) {
            html.append("  <th>").append(escHtml(m)).append("</th>\n");
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
            html.append("  <td class=\"q-label\" title=\"").append(escAttr(q.text())).append("\">").append(escHtml(q.text())).append("</td>\n");
            html.append("  <td class=\"cat\">").append(escHtml(q.category())).append("</td>\n");
            for (String m : modeNames) {
                QuestionResult r = row.get(m);
                if (r == null) {
                    html.append("  <td>-</td>\n");
                } else {
                    String score = r.answerScore();
                    String srcIcon = r.expectedSourceFound() ? "<span class=\"src-found\"> &#10003;</span>" : "<span class=\"src-missing\"> &#10007;</span>";
                    html.append("  <td><span class=\"badge badge-").append(score).append("\">").append(score).append("</span>").append(srcIcon).append("</td>\n");
                }
            }
            html.append("</tr>\n");
        }

        html.append("</tbody>\n</table>\n</div>\n");
    }

    private static void appendCostQuality(StringBuilder html, Map<String, List<QuestionResult>> byMode, List<String> modeNames) {
        html.append("<h2>Cost vs Quality</h2>\n");
        html.append("<div class=\"cq-grid\">\n");

        for (String mode : modeNames) {
            List<QuestionResult> mr = byMode.get(mode);
            long correct = mr.stream().filter(r -> "correct".equals(r.answerScore())).count();
            long totalTokens = mr.stream()
                    .mapToLong(r -> r.ragUsage().totalTokens() + r.answerUsage().totalTokens())
                    .sum();
            long avgRagMs = mr.stream().mapToLong(QuestionResult::ragLatencyMs).sum() / Math.max(mr.size(), 1);
            long avgAnswerMs = mr.stream().mapToLong(QuestionResult::answerLatencyMs).sum() / Math.max(mr.size(), 1);
            long fallbacks = mr.stream().filter(QuestionResult::rerankFellBack).count();

            html.append("<div class=\"cq-card\">\n");
            html.append("  <h3>").append(escHtml(mode)).append("</h3>\n");
            appendCqRow(html, "Correct", correct + " / " + mr.size());
            appendCqRow(html, "Accuracy", String.format("%.0f%%", correct * 100.0 / mr.size()));
            appendCqRow(html, "Total tokens", String.format("%,d", totalTokens));
            appendCqRow(html, "Tokens/correct", correct > 0 ? String.format("%,d", totalTokens / correct) : "n/a");
            appendCqRow(html, "Avg RAG latency", avgRagMs + " ms");
            appendCqRow(html, "Avg answer latency", avgAnswerMs + " ms");
            if (fallbacks > 0) {
                appendCqRow(html, "Reranker fallbacks", fallbacks + "");
            }
            html.append("</div>\n");
        }
        html.append("</div>\n");
    }

    private static void appendCqRow(StringBuilder html, String label, String value) {
        html.append("  <div class=\"cq-row\"><span class=\"cq-label\">").append(label)
                .append("</span><span class=\"cq-value\">").append(value).append("</span></div>\n");
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
            html.append("<details>\n<summary>Q").append(q.number()).append(": ").append(escHtml(q.text())).append("</summary>\n");
            html.append("<div class=\"detail-body\">\n");
            html.append("  <div class=\"detail-expected\"><strong>Expected:</strong> ").append(escHtml(q.expectedAnswer()))
                    .append("<br/><strong>Expected sources:</strong> ").append(escHtml(q.expectedSources().isBlank() ? "(none)" : q.expectedSources()))
                    .append("<br/><strong>Category:</strong> ").append(escHtml(q.category())).append("</div>\n");

            for (String m : modeNames) {
                QuestionResult r = row.get(m);
                if (r == null) continue;
                html.append("  <div class=\"detail-mode-row\">\n");
                html.append("    <span class=\"detail-mode-name\">").append(escHtml(m)).append("</span>\n");
                html.append("    <span class=\"badge badge-").append(r.answerScore()).append("\">").append(r.answerScore()).append("</span>\n");
                String srcList = r.finalSources().isEmpty() ? "none" : String.join(", ", r.finalSources());
                html.append("    <span class=\"detail-sources\">sources: ").append(escHtml(srcList)).append("</span>\n");
                if (r.rerankFellBack()) {
                    html.append("    <span class=\"badge badge-error\">rerank fallback</span>\n");
                }
                html.append("  </div>\n");

                if (!r.effectiveQuery().equals(r.question())) {
                    html.append("  <div style=\"font-size:0.75rem;color:#64748b;margin-left:120px;\">rewritten: <em>")
                            .append(escHtml(r.effectiveQuery())).append("</em></div>\n");
                }

                if (r.judgeReason() != null && !r.judgeReason().isBlank()) {
                    html.append("  <div style=\"font-size:0.75rem;color:#94a3b8;margin-top:0.25rem;\"><strong>Judge:</strong> ")
                            .append(escHtml(r.judgeReason())).append("</div>\n");
                }
                html.append("  <div class=\"detail-full-answer\">").append(escHtml(r.answer())).append("</div>\n");
            }
            html.append("</div>\n</details>\n");
        }
    }

    private static void appendScript(StringBuilder html) {
        html.append("""
                <script>
                // Auto-open the first detail
                document.querySelector('details')?.setAttribute('open','');
                </script>
                """);
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        return escHtml(s).replace("'", "&#39;");
    }
}
