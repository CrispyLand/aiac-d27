package com.crispyland.rag;

import com.crispyland.rag.Day25Evaluator.EvalResults;
import com.crispyland.rag.Day25Evaluator.ScenarioResult;
import com.crispyland.rag.Day25Evaluator.TurnResult;
import java.time.Instant;
import java.util.List;

/**
 * Generates a self-contained HTML report for Day 25 multi-turn RAG evaluation.
 * Shows two scenario conversations with goal/clarification retention metrics.
 */
public final class Day25ReportGenerator {

    private Day25ReportGenerator() {}

    public static String generate(EvalResults eval) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <title>Day 25 \u2014 Multi-turn RAG + Task Memory</title>
                <style>
                """);
        appendStyles(html);
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");
        html.append("<h1>Day 25 \u2014 Mini-chat with RAG + Task Memory</h1>\n");
        html.append("<p class=\"timestamp\">Generated: ").append(Instant.now()).append("</p>\n");

        appendOverview(html, eval);

        for (ScenarioResult scenario : eval.scenarios()) {
            appendScenario(html, scenario);
        }

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }

    private static void appendStyles(StringBuilder html) {
        html.append("""
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: #0f172a; color: #e2e8f0; line-height: 1.6; padding: 2rem;
                }
                .container { max-width: 1100px; margin: 0 auto; }
                h1 { font-size: 2rem; margin-bottom: 0.25rem; color: #f8fafc; }
                h2 { font-size: 1.4rem; margin: 2rem 0 1rem; color: #94a3b8;
                     border-bottom: 1px solid #334155; padding-bottom: 0.5rem; }
                h3 { font-size: 1.1rem; margin: 1rem 0 0.5rem; color: #cbd5e1; }
                .timestamp { color: #64748b; font-size: 0.85rem; margin-bottom: 2rem; }

                /* Overview cards */
                .overview { display: grid; grid-template-columns: repeat(auto-fit, minmax(480px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }
                .scorecard {
                    background: #1e293b; border: 1px solid #334155; border-radius: 12px;
                    padding: 1.25rem 1.5rem;
                }
                .scorecard h3 { margin: 0 0 0.75rem; color: #f8fafc; font-size: 1.1rem; }
                .scorecard .desc { color: #64748b; font-size: 0.8rem; margin-bottom: 1rem; }
                .metrics { display: flex; gap: 1.5rem; flex-wrap: wrap; }
                .metric { text-align: center; }
                .metric .val { font-size: 1.8rem; font-weight: 700; font-variant-numeric: tabular-nums; }
                .metric .val.green { color: #4ade80; }
                .metric .val.amber { color: #fbbf24; }
                .metric .val.red { color: #f87171; }
                .metric .label { font-size: 0.7rem; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.05em; }

                /* Scenario conversation */
                .scenario { margin-bottom: 3rem; }
                .goal-banner {
                    background: #1e3a5f; border: 1px solid #2563eb; border-radius: 10px;
                    padding: 0.75rem 1rem; margin-bottom: 1.5rem; font-size: 0.9rem;
                }
                .goal-banner strong { color: #60a5fa; }
                .conversation { display: flex; flex-direction: column; gap: 0.75rem; }

                /* Turn bubble */
                .turn {
                    border-radius: 10px; padding: 1rem 1.25rem;
                    border: 1px solid #334155;
                }
                .turn.user-turn {
                    background: #1e3a5f; border-color: #2563eb44;
                    margin-right: 8%;
                }
                .turn.assistant-turn {
                    background: #1e293b;
                    margin-left: 4%;
                }
                .turn-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 0.5rem; font-size: 0.75rem;
                }
                .turn-who {
                    font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em;
                    color: #94a3b8;
                }
                .turn-meta { color: #64748b; font-variant-numeric: tabular-nums; }

                /* Category badges */
                .badge {
                    display: inline-block; font-size: 0.65rem; font-weight: 700;
                    padding: 2px 8px; border-radius: 4px; text-transform: uppercase;
                    letter-spacing: 0.04em; margin-left: 8px; vertical-align: middle;
                }
                .badge.goal { background: #1e3a5f; color: #60a5fa; border: 1px solid #3b82f6; }
                .badge.clarification { background: #431407; color: #fb923c; border: 1px solid #ea580c; }
                .badge.constraint { background: #450a0a; color: #f87171; border: 1px solid #ef4444; }
                .badge.factual { background: #1a1333; color: #a78bfa; border: 1px solid #6d28d9; }
                .badge.goal-check { background: #052e16; color: #4ade80; border: 1px solid #166534; }
                .badge.clar-check { background: #431407; color: #fbbf24; border: 1px solid #92400e; }

                /* Structured answer rendering */
                .rag-section-title {
                    font-weight: 700; font-size: 0.85rem; color: #f8fafc;
                    margin: 0.75rem 0 0.25rem; padding-bottom: 2px;
                    border-bottom: 1px solid #334155;
                }
                .rag-section-title:first-child { margin-top: 0; }
                .answer-body { white-space: pre-wrap; font-size: 0.9rem; margin: 0.25rem 0 0.5rem; }
                .cite-marker {
                    display: inline-block; background: #1e3a5f; color: #93c5fd;
                    font-size: 0.7rem; font-weight: 700; padding: 0 4px;
                    border-radius: 4px; vertical-align: super; margin: 0 1px;
                }
                .source-list { list-style: none; padding: 0; margin: 0.25rem 0 0.5rem; }
                .source-list li {
                    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                    font-size: 0.8rem; color: #94a3b8; padding: 2px 0;
                }
                .src-idx {
                    display: inline-block; width: 20px; text-align: center;
                    background: #1e3a5f; color: #93c5fd; border-radius: 3px;
                    font-size: 0.7rem; font-weight: 700; margin-right: 4px;
                }
                .src-file { color: #93c5fd; font-weight: 600; }
                .src-section { color: #64748b; }
                .rag-quote {
                    border-left: 3px solid #6366f1; background: #1e1b4b;
                    padding: 0.4rem 0.75rem; margin: 0.4rem 0; border-radius: 0 6px 6px 0;
                    font-size: 0.8rem; color: #a5b4fc; font-style: italic;
                }
                .quote-status {
                    display: inline-block; font-size: 0.6rem; font-weight: 700;
                    padding: 1px 6px; border-radius: 3px; margin-left: 6px;
                    font-style: normal;
                }
                .quote-status.verified { background: #166534; color: #bbf7d0; }
                .quote-status.rejected { background: #991b1b; color: #fecaca; }
                .quote-status.unverified { background: #854d0e; color: #fef08a; }

                /* Source/no-source indicators */
                .src-ok { color: #4ade80; font-size: 0.75rem; }
                .src-miss { color: #f87171; font-size: 0.75rem; }

                /* Judge reason */
                .judge { font-size: 0.75rem; color: #64748b; margin-top: 0.5rem; font-style: italic; }

                /* Retention check result */
                .check-result {
                    display: inline-block; font-size: 0.7rem; font-weight: 700;
                    padding: 2px 8px; border-radius: 4px; margin-left: 8px;
                }
                .check-result.pass { background: #052e16; color: #4ade80; border: 1px solid #166534; }
                .check-result.fail { background: #450a0a; color: #f87171; border: 1px solid #991b1b; }
                """);
    }

    private static void appendOverview(StringBuilder html, EvalResults eval) {
        html.append("<div class=\"overview\">\n");
        for (ScenarioResult s : eval.scenarios()) {
            html.append("<div class=\"scorecard\">\n");
            html.append("<h3>").append(esc(s.name())).append("</h3>\n");
            html.append("<div class=\"desc\">").append(esc(s.description())).append("</div>\n");
            html.append("<div class=\"metrics\">\n");

            // Sources metric
            int pct = s.totalFactualTurns() > 0
                    ? (int) Math.round(100.0 * s.sourcedTurns() / s.totalFactualTurns()) : 0;
            String pctClass = pct >= 80 ? "green" : pct >= 50 ? "amber" : "red";
            html.append("<div class=\"metric\"><div class=\"val ").append(pctClass).append("\">")
                .append(pct).append("%</div><div class=\"label\">Sourced Answers</div></div>\n");

            html.append("<div class=\"metric\"><div class=\"val\">")
                .append(s.sourcedTurns()).append("/").append(s.totalFactualTurns())
                .append("</div><div class=\"label\">Factual w/ Sources</div></div>\n");

            // Goal retention
            String goalClass = s.goalRetainedOnCheck() ? "green" : "red";
            html.append("<div class=\"metric\"><div class=\"val ").append(goalClass).append("\">")
                .append(s.goalRetainedOnCheck() ? "\u2713" : "\u2717")
                .append("</div><div class=\"label\">Goal Retained</div></div>\n");

            // Clarification retention
            String clarClass = s.clarificationRetainedOnCheck() ? "green" : "red";
            html.append("<div class=\"metric\"><div class=\"val ").append(clarClass).append("\">")
                .append(s.clarificationRetainedOnCheck() ? "\u2713" : "\u2717")
                .append("</div><div class=\"label\">Clarification Retained</div></div>\n");

            // Total turns
            html.append("<div class=\"metric\"><div class=\"val\">")
                .append(s.turns().size())
                .append("</div><div class=\"label\">Turns</div></div>\n");

            html.append("</div>\n"); // metrics
            html.append("</div>\n"); // scorecard
        }
        html.append("</div>\n"); // overview
    }

    private static void appendScenario(StringBuilder html, ScenarioResult scenario) {
        html.append("<div class=\"scenario\">\n");
        html.append("<h2>Scenario: ").append(esc(scenario.name())).append("</h2>\n");
        html.append("<div class=\"goal-banner\"><strong>Goal:</strong> ")
            .append(esc(scenario.goal())).append("</div>\n");

        html.append("<div class=\"conversation\">\n");
        for (TurnResult turn : scenario.turns()) {
            appendTurn(html, turn);
        }
        html.append("</div>\n"); // conversation
        html.append("</div>\n"); // scenario
    }

    private static void appendTurn(StringBuilder html, TurnResult turn) {
        // User message
        html.append("<div class=\"turn user-turn\">\n");
        html.append("<div class=\"turn-header\">");
        html.append("<span class=\"turn-who\">User \u2014 Turn ").append(turn.number()).append("</span>");
        html.append(categoryBadge(turn.category()));
        html.append("</div>\n");
        html.append("<div>").append(esc(turn.question())).append("</div>\n");
        html.append("</div>\n");

        // Assistant response
        html.append("<div class=\"turn assistant-turn\">\n");
        html.append("<div class=\"turn-header\">");
        html.append("<span class=\"turn-who\">Assistant</span>");
        html.append("<span class=\"turn-meta\">");
        if (turn.hasSources()) {
            html.append("<span class=\"src-ok\">\u2713 sources</span> ");
        } else if (!"goal".equals(turn.category()) && !"clarification".equals(turn.category())
                && !"constraint".equals(turn.category())) {
            html.append("<span class=\"src-miss\">\u2717 no sources</span> ");
        }
        html.append(turn.answerLatencyMs()).append("ms");
        if (turn.answerUsage() != null && turn.answerUsage().totalTokens() > 0) {
            html.append(" \u00b7 ").append(turn.answerUsage().totalTokens()).append(" tok");
        }
        html.append("</span>");

        // Retention check badges
        if ("goal-check".equals(turn.category())) {
            html.append(turn.goalRetained()
                    ? "<span class=\"check-result pass\">GOAL RETAINED</span>"
                    : "<span class=\"check-result fail\">GOAL LOST</span>");
        }
        if ("clarification-check".equals(turn.category())) {
            html.append(turn.clarificationRetained()
                    ? "<span class=\"check-result pass\">CLARIFICATION RETAINED</span>"
                    : "<span class=\"check-result fail\">CLARIFICATION LOST</span>");
        }
        html.append("</div>\n");

        // Render structured answer
        RagAnswer parsed = turn.parsedAnswer();
        if (parsed != null && parsed.answer() != null && !parsed.answer().isBlank()) {
            // Answer section
            html.append("<div class=\"rag-section-title\">Answer</div>\n");
            String ansHtml = esc(parsed.answer())
                    .replaceAll("\\[(\\d+)]", "<span class=\"cite-marker\">$1</span>");
            html.append("<div class=\"answer-body\">").append(ansHtml).append("</div>\n");

            // Sources
            if (parsed.hasSources()) {
                html.append("<div class=\"rag-section-title\">Sources</div>\n");
                html.append("<ul class=\"source-list\">\n");
                int idx = 1;
                for (RagAnswer.Source src : parsed.sources()) {
                    html.append("<li><span class=\"src-idx\">").append(idx++).append("</span>");
                    html.append("<span class=\"src-file\">").append(esc(src.file())).append("</span>");
                    if (src.section() != null && !src.section().isBlank()) {
                        html.append(" <span class=\"src-section\">| ").append(esc(src.section())).append("</span>");
                    }
                    if (src.chunkId() != null && !src.chunkId().isBlank()) {
                        html.append(" <span class=\"src-section\">(").append(esc(src.chunkId())).append(")</span>");
                    }
                    html.append("</li>\n");
                }
                html.append("</ul>\n");
            }

            // Quotes
            if (parsed.hasQuotes()) {
                html.append("<div class=\"rag-section-title\">Quotes</div>\n");
                for (RagAnswer.Quote q : parsed.quotes()) {
                    html.append("<div class=\"rag-quote\">\u201c").append(esc(q.text())).append("\u201d");
                    if (q.sourceRef() != null && !q.sourceRef().isBlank()) {
                        html.append(" <span style=\"color:#64748b;font-size:0.7rem;font-style:normal\">\u2014 ")
                            .append(esc(q.sourceRef())).append("</span>");
                    }
                    String statusClass = switch (q.status()) {
                        case VERIFIED -> "verified";
                        case REJECTED -> "rejected";
                        case UNVERIFIED -> "unverified";
                    };
                    html.append(" <span class=\"quote-status ").append(statusClass).append("\">")
                        .append(q.status().name()).append("</span>");
                    html.append("</div>\n");
                }
            }
        } else {
            html.append("<div class=\"answer-body\">").append(esc(turn.rawAnswer())).append("</div>\n");
        }

        // Judge reason
        html.append("<div class=\"judge\">").append(esc(turn.judgeReason())).append("</div>\n");
        html.append("</div>\n"); // assistant turn
    }

    private static String categoryBadge(String category) {
        return switch (category) {
            case "goal" -> "<span class=\"badge goal\">GOAL</span>";
            case "clarification" -> "<span class=\"badge clarification\">CLARIFICATION</span>";
            case "constraint" -> "<span class=\"badge constraint\">CONSTRAINT</span>";
            case "factual" -> "<span class=\"badge factual\">FACTUAL</span>";
            case "goal-check" -> "<span class=\"badge goal-check\">GOAL CHECK</span>";
            case "clarification-check" -> "<span class=\"badge clar-check\">CLARIFICATION CHECK</span>";
            default -> "<span class=\"badge factual\">" + esc(category.toUpperCase()) + "</span>";
        };
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
