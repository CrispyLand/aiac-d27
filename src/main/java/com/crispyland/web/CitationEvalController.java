package com.crispyland.web;

import com.crispyland.rag.CitationEvaluator;
import com.crispyland.rag.CitationReportGenerator;
import com.crispyland.rag.Day25Evaluator;
import com.crispyland.rag.Day25ReportGenerator;
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
 * Day 24 + Day 25 evaluation endpoints.
 * Only active when {@code rag.evaluation.enabled=true}.
 */
@RestController
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class CitationEvalController {

    private static final Logger log = LoggerFactory.getLogger(CitationEvalController.class);
    private static final Path EVAL_DIR = Path.of("./data/eval/day24");
    private static final Path EVAL_DIR_25 = Path.of("./data/eval/day25");

    private final CitationEvaluator evaluator;
    private final Day25Evaluator day25Evaluator;

    public CitationEvalController(CitationEvaluator evaluator, Day25Evaluator day25Evaluator) {
        this.evaluator = evaluator;
        this.day25Evaluator = day25Evaluator;
    }

    @GetMapping(value = "/rag/evaluate/day24", produces = MediaType.TEXT_HTML_VALUE)
    public String evaluateDay24(@RequestParam(defaultValue = "2000") long delay) {
        log.info("Starting Day 24 citation evaluation with delay={}ms", delay);
        CitationEvaluator.EvalResults eval = evaluator.evaluateAll(delay);
        String htmlReport = CitationReportGenerator.generate(eval);
        saveReport(htmlReport);
        return htmlReport;
    }

    @GetMapping(value = "/rag/evaluate/day24/latest", produces = MediaType.TEXT_HTML_VALUE)
    public String latestDay24Report() {
        Optional<Path> latest = findLatestHtml();
        if (latest.isEmpty()) {
            return "<html><body><h1>No Day 24 evaluation report found</h1>"
                    + "<p>Run <a href='/rag/evaluate/day24'>/rag/evaluate/day24</a> first.</p>"
                    + "</body></html>";
        }
        try {
            return Files.readString(latest.get());
        } catch (IOException e) {
            return "<html><body><h1>Error reading report</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }

    private void saveReport(String html) {
        try {
            Files.createDirectories(EVAL_DIR);
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path file = EVAL_DIR.resolve("day24-" + timestamp + ".html");
            Files.writeString(file, html);
            log.info("Day 24 report saved to {}", file);
        } catch (IOException e) {
            log.warn("Could not save Day 24 report: {}", e.getMessage());
        }
    }

    private Optional<Path> findLatestHtml() {
        return findLatestHtml(EVAL_DIR);
    }

    // --- Day 25 endpoints ---

    @GetMapping(value = "/rag/evaluate/day25", produces = MediaType.TEXT_HTML_VALUE)
    public String evaluateDay25(@RequestParam(defaultValue = "2000") long delay) {
        log.info("Starting Day 25 multi-turn evaluation with delay={}ms", delay);
        Day25Evaluator.EvalResults eval = day25Evaluator.evaluateAll(delay);
        String htmlReport = Day25ReportGenerator.generate(eval);
        saveDay25Report(htmlReport);
        return htmlReport;
    }

    @GetMapping(value = "/rag/evaluate/day25/latest", produces = MediaType.TEXT_HTML_VALUE)
    public String latestDay25Report() {
        Optional<Path> latest = findLatestHtml(EVAL_DIR_25);
        if (latest.isEmpty()) {
            return "<html><body style='background:#0f172a;color:#e2e8f0;font-family:sans-serif;padding:2rem'>"
                    + "<h1>No Day 25 evaluation report found</h1>"
                    + "<p>Run <a href='/rag/evaluate/day25' style='color:#60a5fa'>/rag/evaluate/day25</a> first.</p>"
                    + "</body></html>";
        }
        try {
            return Files.readString(latest.get());
        } catch (IOException e) {
            return "<html><body><h1>Error reading report</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }

    private void saveDay25Report(String html) {
        try {
            Files.createDirectories(EVAL_DIR_25);
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path file = EVAL_DIR_25.resolve("day25-" + timestamp + ".html");
            Files.writeString(file, html);
            log.info("Day 25 report saved to {}", file);
        } catch (IOException e) {
            log.warn("Could not save Day 25 report: {}", e.getMessage());
        }
    }

    private static Optional<Path> findLatestHtml(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".html"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
