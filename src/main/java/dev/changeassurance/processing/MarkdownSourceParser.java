package dev.changeassurance.processing;

import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

final class MarkdownSourceParser {
    ParsedSource parse(String source, SourceLines lines) {
        // Preserve BOM in quoted content while allowing the first heading to be recognized.
        String input = source.startsWith("\ufeff") ? source.substring(1) : source;
        Node document = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(input);
        var regions = new ArrayList<ParsedSource.Region>();
        var headings = new ArrayList<Section>();
        var boundaries = new ArrayList<Integer>();
        int start = 1;
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            var spans = block.getSourceSpans();
            if (spans.isEmpty()) {
                throw new SourceParsingException(ProcessingResult.Reason.PARSE_FAILURE,
                        "Markdown block lacks source coordinates");
            }
            int first = spans.getFirst().getLineIndex() + 1;
            int last = spans.getLast().getLineIndex() + 1;
            if (block instanceof Heading heading) {
                add(start, first - 1, headings, boundaries, regions);
                while (!headings.isEmpty() && headings.getLast().level() >= heading.getLevel()) {
                    headings.removeLast();
                }
                headings.add(new Section(heading.getLevel(), lines.slice(first, last).strip()));
                boundaries = new ArrayList<>();
                start = first;
            }
            // Both sides of a block are safe split points; fences/lists stay whole if they fit.
            if (first > start) {
                boundaries.add(first - 1);
            }
            boundaries.add(last);
        }
        add(start, lines.size(), headings, boundaries, regions);
        return new ParsedSource(regions);
    }

    private void add(int start, int end, List<Section> headings, List<Integer> boundaries,
                     List<ParsedSource.Region> regions) {
        if (start <= end) {
            var ends = new ArrayList<>(boundaries);
            ends.add(end);
            regions.add(new ParsedSource.Region(start, end,
                    headings.stream().map(Section::text).toList(), ends.stream().distinct().sorted().toList()));
        }
    }

    private record Section(int level, String text) { }
}
