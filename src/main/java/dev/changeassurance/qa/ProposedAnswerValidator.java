package dev.changeassurance.qa;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.changeassurance.qa.QaException.MalformedReason.*;

/** Validates structure and exact citation identities, never semantic entailment. No prose repair. */
public final class ProposedAnswerValidator {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final int MAX_RESPONSE_BYTES = 20000;

    public Validated validate(String response, EvidenceContextBuilder.Context context) {
        if (response == null) throw QaException.malformed(NO_MODEL_RESPONSE);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(response)) throw QaException.malformed(INVALID_JSON);
        if (response.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)
            throw QaException.malformed(RESPONSE_SIZE_LIMIT_VIOLATION);
        JsonNode root;
        try { root = JSON.readTree(response); }
        catch (tools.jackson.core.JacksonException exception) { throw QaException.malformed(INVALID_JSON); }
        fields(root, Set.of("status", "claims", "uncertainties"), UNEXPECTED_OR_MISSING_FIELDS);
        var statusNode = root.get("status");
        if (!statusNode.isString()) throw QaException.malformed(INVALID_STATUS);
        GroundedAnswer.Status status;
        try { status = GroundedAnswer.Status.valueOf(statusNode.asString()); }
        catch (IllegalArgumentException exception) { throw QaException.malformed(INVALID_STATUS); }
        var claimNodes = root.get("claims");
        var uncertaintyNodes = root.get("uncertainties");
        if (!claimNodes.isArray() || claimNodes.size() > 8) throw QaException.malformed(INVALID_CLAIM_STRUCTURE);
        if (!uncertaintyNodes.isArray() || uncertaintyNodes.size() > 8)
            throw QaException.malformed(INVALID_UNCERTAINTY_STRUCTURE);
        var uncertainties = new ArrayList<String>();
        for (var node : uncertaintyNodes) uncertainties.add(boundedText(node, 500, INVALID_UNCERTAINTY_STRUCTURE));
        var allowed = new LinkedHashMap<String, EvidenceContextBuilder.Entry>();
        for (var entry : context.entries()) {
            EvidenceContextBuilder.verifyEvidence(context.publication(), entry.evidence());
            if (allowed.put(entry.key(), entry) != null) throw new QaException(QaException.Code.INVALID_CITATION);
        }
        var claims = new ArrayList<GroundedAnswer.Claim>();
        var citations = new LinkedHashMap<String, GroundedAnswer.Citation>();
        for (var node : claimNodes) {
            fields(node, Set.of("text", "evidenceKeys"), INVALID_CLAIM_STRUCTURE);
            String text = boundedText(node.get("text"), 1000, INVALID_CLAIM_STRUCTURE);
            var references = node.get("evidenceKeys");
            if (!references.isArray() || references.isEmpty() || references.size() > 8) {
                throw new QaException(QaException.Code.INVALID_CITATION);
            }
            var keys = new LinkedHashSet<String>();
            for (var reference : references) {
                if (!reference.isString() || !allowed.containsKey(reference.asString())) throw new QaException(QaException.Code.INVALID_CITATION);
                var key = reference.asString();
                keys.add(key);
                citations.putIfAbsent(key, new GroundedAnswer.Citation(key, context.publication().id(), allowed.get(key).evidence()));
            }
            claims.add(new GroundedAnswer.Claim(text, List.copyOf(keys)));
        }
        if (status == GroundedAnswer.Status.INSUFFICIENT_EVIDENCE ? !claims.isEmpty() || uncertainties.isEmpty() : claims.isEmpty())
            throw QaException.malformed(INVALID_STATUS_CLAIMS_COMBINATION);
        if (status == GroundedAnswer.Status.PARTIALLY_SUPPORTED && uncertainties.isEmpty())
            throw QaException.malformed(INVALID_STATUS_CLAIMS_COMBINATION);
        String answer = claims.isEmpty() ? "The selected evidence is insufficient to answer this question."
                : String.join("\n", claims.stream().map(GroundedAnswer.Claim::text).toList());
        if (answer.length() > 4000) throw QaException.malformed(TEXT_LIMIT_VIOLATION);
        return new Validated(status, claims, List.copyOf(citations.values()), uncertainties, answer);
    }

    private static String boundedText(JsonNode node, int maxLength, QaException.MalformedReason structureReason) {
        if (node == null || !node.isString()) throw QaException.malformed(structureReason);
        if (node.asString().isBlank() || node.asString().length() > maxLength
                || node.asString().indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(node.asString()))
            throw QaException.malformed(TEXT_LIMIT_VIOLATION);
        return node.asString();
    }

    private static void fields(JsonNode node, Set<String> names, QaException.MalformedReason reason) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(names)) throw QaException.malformed(reason);
    }
    public record Validated(GroundedAnswer.Status status, List<GroundedAnswer.Claim> claims,
                            List<GroundedAnswer.Citation> citations, List<String> uncertainties, String answer) {
        public Validated {
            claims = List.copyOf(claims); citations = List.copyOf(citations); uncertainties = List.copyOf(uncertainties);
        }
    }
}
