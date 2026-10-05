package com.crispyland.web;

import com.crispyland.rag.RagEvalReportGenerator;
import com.crispyland.rag.RagEvaluator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers the RAG evaluation suite and returns Markdown or HTML reports.
 * Only active when {@code rag.evaluation.enabled=true}.
 * <p>
 * GET /rag/evaluate?delay=2000          — run evaluation, return Markdown
 * GET /rag/evaluate/report?delay=2000   — run evaluation, return HTML report
 * GET /rag/evaluate/latest              — serve the most recent HTML report
 */
@RestController
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class RagEvalController {

    private static final Logger log = LoggerFactory.getLogger(RagEvalController.class);
    private static final Path EVAL_DIR = Path.of("./data/eval");

    private final RagEvaluator evaluator;

    public RagEvalController(RagEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @GetMapping(value = "/rag/evaluate", produces = MediaType.TEXT_PLAIN_VALUE)
    public String evaluate(@RequestParam(defaultValue = "2000") long delay) {
        log.info("Starting RAG evaluation with delay={}ms", delay);
        RagEvaluator.EvalResults eval = evaluator.evaluateAll(delay);
        String markdown = RagEvaluator.buildMarkdown(eval);

        saveReports(eval, markdown);
        return markdown;
    }

    @GetMapping(value = "/rag/evaluate/report", produces = MediaType.TEXT_HTML_VALUE)
    public String evaluateHtml(@RequestParam(defaultValue = "2000") long delay) {
        log.info("Starting RAG evaluation (HTML report) with delay={}ms", delay);
        RagEvaluator.EvalResults eval = evaluator.evaluateAll(delay);
        String htmlReport = RagEvalReportGenerator.generate(eval);

        saveReports(eval, RagEvaluator.buildMarkdown(eval));
        return htmlReport;
    }

    @GetMapping(value = "/rag/evaluate/latest", produces = MediaType.TEXT_HTML_VALUE)
    public String latestReport() {
        Optional<Path> latest = findLatestHtml();
        if (latest.isEmpty()) {
            return "<html><body><h1>No evaluation report found</h1><p>Run <a href='/rag/evaluate/report'>/rag/evaluate/report</a> first.</p></body></html>";
        }
        try {
            return Files.readString(latest.get());
        } catch (IOException e) {
            return "<html><body><h1>Error reading report</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }

    private void saveReports(RagEvaluator.EvalResults eval, String markdown) {
        try {
            Files.createDirectories(EVAL_DIR);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

            Path mdFile = EVAL_DIR.resolve("eval-" + timestamp + ".md");
            Files.writeString(mdFile, markdown);
            log.info("Markdown report saved to {}", mdFile);

            String htmlReport = RagEvalReportGenerator.generate(eval);
            Path htmlFile = EVAL_DIR.resolve("eval-" + timestamp + ".html");
            Files.writeString(htmlFile, htmlReport);
            log.info("HTML report saved to {}", htmlFile);
        } catch (IOException e) {
            log.warn("Could not save evaluation reports: {}", e.getMessage());
        }
    }

    private Optional<Path> findLatestHtml() {
        try (Stream<Path> files = Files.list(EVAL_DIR)) {
            return files.filter(p -> p.toString().endsWith(".html"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
