import type { Citation, EvidenceReference } from '../api/types';
export function CitationButton({ citation, onEvidence }: { citation: Citation; onEvidence?: ((reference: EvidenceReference, key?: string) => void) | undefined }) {
    const { key, evidence } = citation;
    return <button className="citation-button" disabled={!onEvidence} onClick={() => onEvidence?.(evidence, key)}
        aria-label={`Open citation ${key}: ${evidence.path} lines ${evidence.startLine}–${evidence.endLine}`}>
        <strong>{key}</strong><span>{evidence.path.split('/').at(-1)}:{evidence.startLine}–{evidence.endLine}</span><span aria-hidden="true">↗</span>
    </button>;
}
