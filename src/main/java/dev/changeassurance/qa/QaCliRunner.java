package dev.changeassurance.qa;

import dev.changeassurance.ingestion.GitSnapshotIngestor;
import dev.changeassurance.ingestion.RepositorySelection;
import dev.changeassurance.persistence.EvidencePersistenceService;
import dev.changeassurance.processing.ArtifactProcessor;
import dev.changeassurance.processing.ChunkingConfiguration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.convert.ConversionException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Explicit developer command; ingestion/publication stays outside the answering service. */
@Component
@Profile("qa")
@ConditionalOnProperty(name = "qa.command")
public final class QaCliRunner implements ApplicationRunner {
    private final Environment environment;
    private final EvidencePersistenceService persistence;
    private final GroundedQuestionAnsweringService answering;
    private final ConfigurableApplicationContext application;

    public QaCliRunner(Environment environment, EvidencePersistenceService persistence,
                       GroundedQuestionAnsweringService answering, ConfigurableApplicationContext application) {
        this.environment = environment; this.persistence = persistence;
        this.answering = answering; this.application = application;
    }

    @Override
    public void run(ApplicationArguments args) {
        var json = new JsonMapper().writerWithDefaultPrettyPrinter();
        try {
            Object output = switch (required("qa.command")) {
                case "publish" -> {
                    RepositorySelection selection;
                    ChunkingConfiguration configuration;
                    try {
                        String includes = environment.getProperty("qa.scope-includes");
                        if (includes != null && includes.chars().anyMatch(Character::isISOControl))
                            throw new IllegalArgumentException("Scope must not contain control characters");
                        var scope = includes == null ? RepositorySelection.Scope.all() : new RepositorySelection.Scope(
                                Arrays.stream(includes.split(",", -1)).map(String::strip).toList(), List.of());
                        selection = new RepositorySelection(Path.of(required("qa.publish-repository")),
                                required("qa.repository-id"), required("qa.revision"), scope);
                        for (String key : List.of("qa.chunk-max-lines", "qa.chunk-max-chunks"))
                            if (environment.containsProperty(key) && environment.getProperty(key).isBlank())
                                throw new IllegalArgumentException("Chunk limits must not be blank");
                        configuration = new ChunkingConfiguration(
                                environment.getProperty("qa.chunk-max-lines", Integer.class, 80),
                                environment.getProperty("qa.chunk-max-chunks", Integer.class, 10000));
                    } catch (IllegalArgumentException | ConversionException exception) {
                        throw new QaException(QaException.Code.INVALID_REQUEST);
                    }
                    var ingestion = new GitSnapshotIngestor().ingest(selection);
                    var publication = persistence.publish(ingestion, new ArtifactProcessor().process(ingestion, configuration));
                    yield Map.of("publicationId", publication.id(), "snapshot", publication.ingestion().snapshot(),
                            "processingStatus", publication.processing().status(), "configuration", configuration);
                }
                case "answer" -> {
                    var defaults = AnsweringConfiguration.defaults();
                    var configuration = new AnsweringConfiguration(
                            environment.getProperty("qa.top-k", Integer.class, defaults.topK()),
                            environment.getProperty("qa.max-evidence", Integer.class, defaults.maxEvidence()),
                            environment.getProperty("qa.max-context-bytes", Integer.class, defaults.maxContextBytes()),
                            environment.getProperty("qa.max-evidence-bytes", Integer.class, defaults.maxEvidenceBytes()));
                    var run = answering.answerWithRetrieval(required("qa.publication-id"), required("qa.question"), configuration);
                    // Preserve the existing answer fields; add the actual retrieval trace from this invocation.
                    var outputNode = (tools.jackson.databind.node.ObjectNode) new JsonMapper().valueToTree(run.answer());
                    outputNode.put("requestedRetrievalStrategy", QaConfiguration.strategy(environment).name());
                    if (QaConfiguration.strategy(environment) != dev.changeassurance.retrieval.RetrievalStrategy.KEYWORD)
                        outputNode.put("embeddingProfileId", required("qa.embedding-profile-id"));
                    outputNode.set("retrieval", new JsonMapper().valueToTree(run.retrieval()));
                    yield outputNode;
                }
                default -> throw new QaException(QaException.Code.INVALID_REQUEST);
            };
            System.out.println(json.writeValueAsString(output));
        } catch (QaException exception) {
            Object failure = exception.getCause() instanceof dev.changeassurance.retrieval.VectorRetrievalException vector
                    && vector.coverage() != null ? Map.of("failure", exception.code(), "coverage", vector.coverage())
                    : Map.of("failure", exception.code());
            System.err.println(json.writeValueAsString(failure));
            // Keep provider/SQL bodies and nested exception details out of the command's public error.
            throw new QaException(exception.code());
        } finally {
            application.close();
        }
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new QaException(QaException.Code.INVALID_REQUEST);
        return value;
    }
}
