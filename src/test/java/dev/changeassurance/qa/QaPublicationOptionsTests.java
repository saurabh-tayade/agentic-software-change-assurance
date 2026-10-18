package dev.changeassurance.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class QaPublicationOptionsTests {
    @ParameterizedTest
    @CsvSource(value = {
            "qa.scope-includes|''", "qa.scope-includes|'   '", "qa.scope-includes|/src",
            "qa.scope-includes|../src", "qa.scope-includes|src/../tests", "qa.scope-includes|.git",
            "qa.scope-includes|src,", "qa.scope-includes|,src", "qa.scope-includes|src,,tests",
            "qa.chunk-max-lines|0", "qa.chunk-max-lines|10001", "qa.chunk-max-lines|abc",
            "qa.chunk-max-chunks|0", "qa.chunk-max-chunks|-1", "qa.chunk-max-chunks|100001",
            "qa.chunk-max-chunks|abc", "qa.chunk-max-chunks|1.5", "qa.chunk-max-chunks|2147483648",
            "qa.chunk-max-lines|''", "qa.chunk-max-chunks|''"
    }, delimiter = '|')
    void rejectsInvalidOptionsBeforeOpeningRepositoryOrPublishing(String property, String value) {
        reject(property, value);
    }
    @Test void preservesDomainScopeCountLengthAndControlCharacterLimits() {
        reject("qa.scope-includes", String.join(",", java.util.Collections.nCopies(101, "src")));
        reject("qa.scope-includes", "x".repeat(4097));
        reject("qa.scope-includes", "sr\nc");
        reject("qa.scope-includes", "src\n");
        reject("qa.scope-includes", "src\\test");
    }
    private void reject(String property, String value) {
        var environment = new MockEnvironment().withProperty("qa.command", "publish")
                .withProperty("qa.publish-repository", "/nonexistent/invalid-options-must-fail-before-ingestion")
                .withProperty("qa.repository-id", "parcel-quotes-v1").withProperty("qa.revision", "a".repeat(40))
                .withProperty(property, value);
        var application = new GenericApplicationContext(); application.refresh();
        var runner = new QaCliRunner(environment, null, null, application);
        var failure = assertThrows(QaException.class, () -> runner.run(new DefaultApplicationArguments()));
        assertEquals(QaException.Code.INVALID_REQUEST, failure.code());
        assertNull(failure.getCause());
        assertFalse(application.isActive());
    }
}
