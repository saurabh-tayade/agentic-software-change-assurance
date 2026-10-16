package dev.changeassurance.evaluation;

import java.util.HashSet;
import java.util.List;

/** Source-reference metrics only; deliberately independent of lexical similarity and answer support. */
final class RetrievalGrading {
    record Reference(String path, int start, int end) {
        Reference {
            if (path == null || path.isBlank() || start < 1 || end < start || end - start > 1_000_000)
                throw new IllegalArgumentException("Invalid evaluation reference");
        }
        boolean overlaps(Reference other) {
            return path.equals(other.path) && start <= other.end && end >= other.start;
        }
    }
    record Grade(Double recall, Double reciprocalRank, int coveredReferences, int expectedReferences) { }

    static Grade grade(List<Reference> expected, List<Reference> ranked) {
        if (expected.isEmpty()) return new Grade(null, null, 0, 0);
        int covered = 0;
        for (var ref : expected) {
            var lines = new HashSet<Integer>();
            for (var hit : ranked) {
                if (ref.overlaps(hit)) {
                    for (int line = Math.max(ref.start, hit.start); line <= Math.min(ref.end, hit.end); line++) lines.add(line);
                }
            }
            if (lines.size() == ref.end - ref.start + 1) covered++;
        }
        double rr = 0;
        for (int i = 0; i < ranked.size(); i++) {
            var hit = ranked.get(i);
            if (expected.stream().anyMatch(ref -> ref.overlaps(hit))) {
                rr = 1.0 / (i + 1);
                break;
            }
        }
        return new Grade((double) covered / expected.size(), rr, covered, expected.size());
    }
}
