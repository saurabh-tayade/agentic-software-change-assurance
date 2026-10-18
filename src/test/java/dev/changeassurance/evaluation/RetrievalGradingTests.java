package dev.changeassurance.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.changeassurance.evaluation.RetrievalGrading.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalGradingTests {
    @Test void reproducesEveryHistoricalPerCaseGradeWithoutDatabaseOrModel() throws Exception {
        var definition = EvaluationFixtures.definition();
        var baseline = RetrievalComparison.JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("evaluation/retrieval/baseline-v1.json")));
        int compared = 0;
        for (var row : baseline.path("scenarios")) {
            var scenario = definition.scenarios().stream().filter(s -> s.id().equals(row.path("id").asString())).findFirst().orElseThrow();
            var ranked = new java.util.ArrayList<Reference>();
            for (var match : row.path("matches")) ranked.add(new Reference(match.path("path").asString(),
                    match.path("start_line").asInt(), match.path("end_line").asInt()));
            assertEquals(RetrievalComparison.JSON.treeToValue(row.path("grade"), Grade.class), grade(scenario.expected(), ranked));
            compared++;
        }
        assertEquals(15, compared);
    }
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
