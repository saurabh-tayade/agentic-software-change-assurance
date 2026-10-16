package dev.changeassurance.processing;

import java.util.ArrayList;
import java.util.List;

/** Original UTF-16 offsets; LF, CRLF and CR each terminate one physical line. */
final class SourceLines {
    private final String source;
    private final List<Integer> starts = new ArrayList<>();

    SourceLines(String source) {
        this.source = source;
        if (!source.isEmpty()) {
            starts.add(0);
        }
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                i++;
            }
            if ((c == '\r' || c == '\n') && i + 1 < source.length()) {
                starts.add(i + 1);
            }
        }
    }

    int size() {
        return starts.size();
    }

    String slice(int start, int end) {
        if (start < 1 || end < start || end > size()) {
            throw new IllegalArgumentException("Parser returned an invalid original line range");
        }
        return source.substring(starts.get(start - 1), end == size() ? source.length() : starts.get(end));
    }
}
