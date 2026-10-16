package dev.changeassurance.processing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.changeassurance.processing.ProcessingResult.Reason.*;

final class JavaSourceParser {
    ParsedSource parse(String source, SourceLines lines) {
        // Escape translation can introduce logical newlines. Fail closed rather than miscite them.
        if (source.contains("\\" + "u")) {
            throw new SourceParsingException(UNSUPPORTED_CONSTRUCT,
                    "Java Unicode escape candidates are unsupported by source-lines-v1");
        }
        String input = source.startsWith("\ufeff") ? " " + source.substring(1) : source;
        var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        var result = parser.parse(input);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String detail = result.getProblems().stream().limit(5)
                    .map(problem -> problem.getMessage()).reduce((a, b) -> a + "; " + b).orElse("No syntax tree");
            throw new SourceParsingException(PARSE_FAILURE, detail.substring(0, Math.min(2000, detail.length())));
        }
        var unit = result.getResult().orElseThrow();
        var nodes = new ArrayList<Node>();
        unit.getPackageDeclaration().ifPresent(nodes::add);
        nodes.addAll(unit.getImports());
        nodes.addAll(unit.getTypes());
        unit.getModule().ifPresent(nodes::add);
        var regions = new ArrayList<ParsedSource.Region>();
        partition(1, lines.size(), nodes, List.of(), regions);
        return new ParsedSource(regions);
    }

    private void partition(int start, int end, List<? extends Node> nodes, List<String> context,
                           List<ParsedSource.Region> output) {
        var sorted = nodes.stream().sorted(Comparator.comparingInt(this::start)).toList();
        int cursor = start;
        for (int i = 0; i < sorted.size();) {
            Node node = sorted.get(i++);
            int first = Math.max(start, start(node));
            int last = node.getRange().orElseThrow().end.line;
            var group = new ArrayList<Node>();
            group.add(node);
            while (i < sorted.size() && start(sorted.get(i)) <= last) {
                Node next = sorted.get(i++);
                group.add(next);
                last = Math.max(last, next.getRange().orElseThrow().end.line);
            }
            if (first < cursor || last > end) {
                throw new SourceParsingException(PARSE_FAILURE, "Inconsistent Java source ranges");
            }
            add(cursor, first - 1, context, output);
            var nested = new ArrayList<>(context);
            for (Node member : group) {
                nested.add(label(member));
            }
            if (group.size() == 1 && node instanceof TypeDeclaration<?> type) {
                partition(first, last, type.getMembers(), nested, output);
            } else {
                var boundaries = new ArrayList<Integer>();
                boundaries.add(last);
                if (group.size() == 1) {
                    // Immediate statements protect a fitting if/loop/block inside an oversized method.
                    node.getChildNodes().stream().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast)
                            .flatMap(body -> body.getStatements().stream()).forEach(statement -> {
                                var range = statement.getRange().orElseThrow();
                                boundaries.add(range.begin.line - 1);
                                boundaries.add(range.end.line);
                            });
                }
                output.add(new ParsedSource.Region(first, last, nested, boundaries));
            }
            cursor = last + 1;
        }
        add(cursor, end, context, output);
    }

    private int start(Node node) {
        int begin = node.getRange().orElseThrow().begin.line;
        return node.getComment().flatMap(Node::getRange).map(range -> Math.min(begin, range.begin.line)).orElse(begin);
    }

    private String label(Node node) {
        if (node instanceof TypeDeclaration<?> type) {
            return type.getClass().getSimpleName() + ": " + type.getNameAsString();
        }
        if (node instanceof CallableDeclaration<?> callable) {
            return callable.getClass().getSimpleName() + ": " + callable.getSignature().asString();
        }
        if (node instanceof CompactConstructorDeclaration constructor) {
            return "CompactConstructorDeclaration: " + constructor.getNameAsString();
        }
        return node.getClass().getSimpleName();
    }

    private void add(int start, int end, List<String> context, List<ParsedSource.Region> output) {
        if (start <= end) {
            output.add(new ParsedSource.Region(start, end, context, List.of(end)));
        }
    }
}
