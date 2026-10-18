import type { AnswerResponse, CapabilitiesResponse, EvidenceResponse, PublicationDetailResponse, PublicationReference } from '../api/types';

export const version = 'repository-intelligence-v1' as const;
export const full: PublicationReference = {
    publicationId: 'a'.repeat(64), snapshotId: '1'.repeat(64), derivationId: '2'.repeat(64), repositoryId: 'parcel-quotes-v1',
    commitSha: 'd'.repeat(40), scope: { includes: [], excludes: [] }, policyVersion: 'git-snapshot-v1',
    processingConfiguration: { identity: 'source-lines-v1:lines=4:chunks=10000', maxLines: 4, maxChunks: 10000 }, processingStatus: 'COMPLETE',
};
export const scoped: PublicationReference = { ...full, publicationId: 'b'.repeat(64), snapshotId: '3'.repeat(64), scope: { includes: ['src'], excludes: [] } };
export const limited: PublicationReference = { ...full, publicationId: 'c'.repeat(64), processingStatus: 'PARTIAL',
    processingConfiguration: { identity: 'source-lines-v1:lines=4:chunks=1', maxLines: 4, maxChunks: 1 } };
export const capabilities: CapabilitiesResponse = {
    apiVersion: version, configuredRetrievalStrategies: ['KEYWORD', 'VECTOR', 'HYBRID'], defaultRetrievalStrategy: 'KEYWORD',
    embeddingProfileId: 'f'.repeat(64), questionLengthLimit: 512,
    answeringConfiguration: { topK: 12, maxEvidence: 6, maxContextBytes: 6000, maxEvidenceBytes: 3000 },
    generationSettings: { model: 'qwen3:4b-instruct', temperature: 0, contextTokens: 8192, maxOutputTokens: 768, timeoutSeconds: 120 },
    availability: 'CONFIGURED_NOT_RUNTIME_READINESS',
};
export const details = (publication = full): PublicationDetailResponse => ({
    apiVersion: version, publication,
    coverage: { discovered: 5, ingested: 4, excluded: 1, unsupported: 0, failed: 0, artifacts: 4, parsed: 4,
        completeArtifacts: 4, partialArtifacts: 0, failedArtifacts: 0, chunks: publication === limited ? 1 : 18 },
    artifacts: [{ path: 'src/QuotePolicy.java', parser: 'javaparser-core-3.27.1-java21', status: 'COMPLETE', parsed: true, chunkCount: 4, limitations: [] }],
    ingestionCoverage: [], ingestionLimitations: ['Committed source only.'],
    processingLimitations: publication === limited ? ['Chunk budget reached.'] : [],
});
export const evidence: EvidenceResponse = {
    apiVersion: version,
    evidence: { publicationId: full.publicationId, snapshotId: full.snapshotId, chunkId: '5'.repeat(64), repositoryId: full.repositoryId,
        commitSha: full.commitSha, path: 'src/QuotePolicy.java', startLine: 4, endLine: 7, artifactType: 'SOURCE', sourceFormat: 'JAVA',
        gitBlobId: 'e'.repeat(40), sourceHash: '6'.repeat(64), contentHash: '7'.repeat(64), parser: 'javaparser-core-3.27.1-java21',
        chunkingConfiguration: full.processingConfiguration.identity },
    content: '    int shippingCost() {\n        // <script>alert("untrusted")</script>\n        return 800;\n    }\n',
    structuralContext: ['method shippingCost'],
};
export const answer: AnswerResponse = {
    apiVersion: version, answerVersion: 'grounded-answer-v1', publication: details(), question: 'Where is shipping cost calculated?',
    answer: 'Shipping cost is calculated in QuotePolicy.', status: 'SUPPORTED',
    claims: [{ text: 'Shipping cost is calculated in QuotePolicy.', evidenceKeys: ['E5'] }],
    citations: [{ key: 'E5', evidence: evidence.evidence }], uncertainties: ['Deployment behavior is not established.'],
    requestedRetrievalStrategy: 'VECTOR', embeddingProfileId: capabilities.embeddingProfileId, retrievalStrategy: 'postgres-vector-v1:profile=test-profile',
    retrievedEvidence: Array.from({ length: 5 }, (_, i) => ({ rank: i + 1, score: .9 - i * .1,
        evidence: { ...evidence.evidence, chunkId: String(i + 1).repeat(64) } })),
    retrievalLimitations: ['Ranking does not establish semantic support.'],
    contextSelection: { policy: 'complete-ranked-chunks-v1', selected: [{ key: 'E5', rank: 5, chunkId: evidence.evidence.chunkId }],
        omissions: [{ rank: 2, chunkId: '2'.repeat(64), reason: 'CONTEXT_BYTE_LIMIT' }], contextBytes: 2500, limitations: ['Complete chunks only.'] },
    configuration: capabilities.answeringConfiguration, promptVersion: 'repository-qa-v2', promptHash: '8'.repeat(64), schemaHash: '9'.repeat(64),
    generationSettings: capabilities.generationSettings,
    invocation: { provider: 'test-double', model: 'qwen3:4b-instruct', durationMillis: 600, inputTokens: null, outputTokens: 0,
        finishReason: 'stop', returnedModel: null, connectTimeoutSeconds: 5, thinkingEnabled: false, keepAlive: '0s' },
    validation: 'CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED', durationMillis: 800,
};

export const jsonResponse = (data: unknown, status = 200): Response => new Response(JSON.stringify(data), {
    status, headers: { 'Content-Type': status < 400 ? 'application/json' : 'application/problem+json' },
});
