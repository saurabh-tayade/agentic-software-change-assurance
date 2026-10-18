package dev.changeassurance.evaluation;

import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.util.*;
import static dev.changeassurance.evaluation.EvaluationReportException.Code.*;
import static dev.changeassurance.evaluation.EvaluationReportInputs.check;

final class EvaluationReportCommand {
    private EvaluationReportCommand() { }
    static void run(Environment env) {
        String output = env.getProperty("evaluation.report-output"); check(output != null && !output.isBlank(), INVALID_INPUT);
        // JSON array preserves literal commas/spaces in filenames and requires explicit review selection.
        String selected = env.getProperty("evaluation.qa-grades"); List<Path> grades = new ArrayList<>();
        if (selected != null) try {
            var array = EvaluationReportInputs.JSON.readTree(selected); check(array.isArray() && array.size() <= 32, INVALID_INPUT);
            for (var node : array) { check(node.isString() && !node.asString().isBlank(), INVALID_INPUT); grades.add(Path.of(node.asString())); }
        } catch (RuntimeException invalid) { throw new EvaluationReportException(INVALID_INPUT); }
        var generator = new EvaluationReportGenerator();
        var report = generator.generate(new EvaluationReportGenerator.Request(path(env, "evaluation.retrieval-execution"),
                path(env, "evaluation.retrieval-grades"), path(env, "evaluation.qa-execution"), grades));
        Path directory = generator.write(Path.of(output), report);
        System.out.println("OFFLINE EVALUATION REPORT: json=" + directory.resolve("report.json") + " markdown=" + directory.resolve("report.md"));
    }
    private static Path path(Environment env, String name) {
        String value = env.getProperty(name); if (value == null) return null;
        check(!value.isBlank(), INVALID_INPUT); return Path.of(value);
    }
}
