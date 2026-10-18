/** Wire contracts from RepositoryIntelligenceDtos.java (M1.9a). No persistence/provider entities. */
export type Strategy = 'KEYWORD' | 'VECTOR' | 'HYBRID';
export type ProcessingStatus = 'COMPLETE' | 'PARTIAL' | 'FAILED';
export type AnswerStatus = 'SUPPORTED' | 'PARTIALLY_SUPPORTED' | 'INSUFFICIENT_EVIDENCE';
export interface Versioned { apiVersion: 'repository-intelligence-v1' }
export interface AnswerRequest { question: string; retrievalStrategy?: Strategy | null }
export interface AnsweringSettings { topK: number; maxEvidence: number; maxContextBytes: number; maxEvidenceBytes: number }
export interface ModelSettings {
    model: string; temperature: number; contextTokens: number; maxOutputTokens: number; timeoutSeconds: number;
}
export interface CapabilitiesResponse extends Versioned {
    configuredRetrievalStrategies: Strategy[]; defaultRetrievalStrategy: Strategy; embeddingProfileId: string | null;
    questionLengthLimit: number; answeringConfiguration: AnsweringSettings; generationSettings: ModelSettings; availability: string;
}
export interface Scope { includes: string[]; excludes: string[] }
export interface ProcessingConfiguration { identity: string; maxLines: number; maxChunks: number }
export interface PublicationReference {
    publicationId: string; snapshotId: string; derivationId: string; repositoryId: string; commitSha: string;
    scope: Scope; policyVersion: string; processingConfiguration: ProcessingConfiguration; processingStatus: ProcessingStatus;
}
export interface PublicationListResponse extends Versioned { items: PublicationReference[]; nextAfter: string | null }
export interface CoverageSummary {
    discovered: number; ingested: number; excluded: number; unsupported: number; failed: number;
    artifacts: number; parsed: number; completeArtifacts: number; partialArtifacts: number; failedArtifacts: number; chunks: number;
}
export interface CoverageEntry { path: string; disposition: string; reason: string; detail: string }
export interface Limitation { reason: string; detail: string }
export interface ArtifactCoverage {
    path: string; parser: string; status: ProcessingStatus; parsed: boolean; chunkCount: number; limitations: Limitation[];
}
export interface PublicationDetailResponse extends Versioned {
    publication: PublicationReference; coverage: CoverageSummary; ingestionCoverage: CoverageEntry[];
    artifacts: ArtifactCoverage[]; ingestionLimitations: string[]; processingLimitations: string[];
}
export interface EvidenceReference {
    publicationId: string; snapshotId: string; chunkId: string; repositoryId: string; commitSha: string;
    path: string; startLine: number; endLine: number; artifactType: string; sourceFormat: string;
    gitBlobId: string; sourceHash: string; contentHash: string; parser: string; chunkingConfiguration: string;
}
export interface EvidenceResponse extends Versioned { evidence: EvidenceReference; content: string; structuralContext: string[] }
export interface Claim { text: string; evidenceKeys: string[] }
export interface Citation { key: string; evidence: EvidenceReference }
export interface RetrievedEvidence { rank: number; score: number; evidence: EvidenceReference }
export interface SelectedEvidence { key: string; rank: number; chunkId: string }
export interface Omission { rank: number; chunkId: string; reason: string }
export interface ContextSelection {
    policy: string; selected: SelectedEvidence[]; omissions: Omission[]; contextBytes: number; limitations: string[];
}
export interface Invocation {
    provider: string; model: string; durationMillis: number; inputTokens: number | null; outputTokens: number | null;
    finishReason: string | null; returnedModel: string | null; connectTimeoutSeconds: number | null;
    thinkingEnabled: boolean | null; keepAlive: string | null;
}
export interface AnswerResponse extends Versioned {
    answerVersion: string; publication: PublicationDetailResponse; question: string; answer: string; status: AnswerStatus;
    claims: Claim[]; citations: Citation[]; uncertainties: string[]; requestedRetrievalStrategy: Strategy;
    embeddingProfileId: string | null; retrievalStrategy: string; retrievedEvidence: RetrievedEvidence[];
    retrievalLimitations: string[]; contextSelection: ContextSelection; configuration: AnsweringSettings;
    promptVersion: string; promptHash: string; schemaHash: string; generationSettings: ModelSettings;
    invocation: Invocation | null; validation: string; durationMillis: number;
}
export interface ProblemResponse { type: string; title: string; status: number; code: string; detail: string }
