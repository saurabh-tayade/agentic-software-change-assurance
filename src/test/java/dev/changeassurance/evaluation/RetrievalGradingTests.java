package dev.changeassurance.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.changeassurance.evaluation.RetrievalGrading.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalGradingTests {
    @Test void requiresCompleteUnionCoverageAndDoesNotDoubleCountOverlap() {
        var expected = List.of(new Reference("A", 3, 7), new Reference("B", 1, 2));
        var grade = grade(expected, List.of(new Reference("irrelevant", 1, 20), new Reference("A", 3, 5),
                new Reference("A", 5, 7), new Reference("B", 1, 1)));
        assertEquals(0.5, grade.recall());
        assertEquals(0.5, grade.reciprocalRank());
        assertEquals(1, grade.coveredReferences());
        assertEquals(0.0, grade(expected, List.of(new Reference("A", 3, 5), new Reference("A", 3, 5))).recall());
    }
    @Test void distinguishesEmptyGroundTruthFromMissedEvidenceAndUsesExactPaths() {
        assertNull(grade(List.of(), List.of()).recall());
        assertNull(grade(List.of(), List.of()).reciprocalRank());
        var expected = List.of(new Reference("src/A.java", 4, 6));
        assertEquals(0.0, grade(expected, List.of()).recall());
        assertEquals(0.0, grade(expected, List.of(new Reference("tests/A.java", 4, 6))).reciprocalRank());
        assertEquals(0.0, grade(expected, List.of(new Reference("src/A.java", 7, 9))).reciprocalRank());
        assertThrows(IllegalArgumentException.class, () -> new Reference("A", 0, 3));
    }
}
