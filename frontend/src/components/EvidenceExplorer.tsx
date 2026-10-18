import { useEffect, useRef } from 'react';
import type { EvidenceReference, EvidenceResponse } from '../api/types';
import type { LoadState } from '../state';
import { EmptyState, ErrorNotice, shortId } from './Common';

interface Props {
    state: LoadState<EvidenceResponse>;
    citation: { reference: EvidenceReference; key: string | undefined } | null;
    onRetry: () => void;
}

export function EvidenceExplorer({ state, citation, onRetry }: Props) {
    const heading = useRef<HTMLHeadingElement>(null);
    useEffect(() => {
        // Move keyboard users to the requested excerpt, including on a stacked mobile layout.
        if (citation) heading.current?.focus();
    }, [citation]);
    const data = state.data;
    const reference = data?.evidence;
    // Keep line terminators in the text nodes, including CRLF and the final newline.
    // Line-number decoration must not concatenate or rewrite the persisted excerpt.
    const lines = data?.content.match(/[^\r\n]*(?:\r\n|\n|\r|$)/g) ?? [];
    if (lines.at(-1) === '') lines.pop();
    return <aside className="panel evidence-panel" aria-labelledby="evidence-title">
        <div className="panel-heading"><div><span className="eyebrow">03 / Source evidence</span>
            <h2 id="evidence-title" tabIndex={-1} ref={heading}>Evidence explorer</h2></div><span className="file-symbol" aria-hidden="true">⌗</span></div>
        {!citation && <EmptyState eyebrow="Follow the source" title="Evidence lives here"><p>Open a citation from an answer to inspect its original source excerpt and provenance.</p></EmptyState>}
        <p className="sr-only" role="status">{reference ? `Source excerpt ready: ${reference.path}, lines ${reference.startLine} to ${reference.endLine}.` : ''}</p>
        {state.loading && <p className="evidence-loading" role="status">Resolving the persisted excerpt and checking its identity…</p>}
        {state.error && <ErrorNotice error={state.error} action={<button className="text-button" onClick={onRetry}>Retry evidence</button>} />}
        {data && reference && <>
            <div className="source-heading"><div className="source-labels">{citation?.key && <span className="citation-key">{citation.key}</span>}
                <span className="badge neutral">{reference.sourceFormat}</span><span className="small muted">Original excerpt</span></div>
                <h3>{reference.path}</h3><p className="small muted">Lines {reference.startLine}–{reference.endLine} · inclusive</p></div>
            <div className="source-code" role="region" aria-label={`Original source, lines ${reference.startLine} to ${reference.endLine}`} tabIndex={0}>
                <pre><code>{lines.map((line, index) => <span className="source-line" key={index}>
                    <span className="line-number" aria-hidden="true">{reference.startLine + index}</span><span>{line}</span>
                </span>)}</code></pre>
            </div>
            <div className="source-context"><p><strong>{reference.repositoryId}</strong></p>
                <p>Revision <code>{shortId(reference.commitSha)}</code></p>
                <p className="small muted">Persisted evidence chunk, not a full-file view. Content is displayed as plain text.</p></div>
            <div className="identity-note"><span aria-hidden="true">◈</span><p><strong>Source identity checked by the API</strong><br />This does not verify the answer’s interpretation.</p></div>
            <details className="source-provenance"><summary>Source provenance</summary><dl className="metadata">
                <dt>Publication</dt><dd className="mono">{reference.publicationId}</dd>
                <dt>Snapshot</dt><dd className="mono">{reference.snapshotId}</dd>
                <dt>Chunk</dt><dd className="mono">{reference.chunkId}</dd>
                <dt>Git revision</dt><dd className="mono">{reference.commitSha}</dd>
                <dt>Git blob</dt><dd className="mono">{reference.gitBlobId || 'Unavailable'}</dd>
                <dt>Source hash</dt><dd className="mono">{reference.sourceHash}</dd>
                <dt>Content hash</dt><dd className="mono">{reference.contentHash}</dd>
                <dt>Artifact type</dt><dd>{reference.artifactType}</dd>
                <dt>Parser</dt><dd>{reference.parser || 'Unavailable'}</dd>
                <dt>Chunking</dt><dd className="mono">{reference.chunkingConfiguration || 'Unavailable'}</dd>
                <dt>Structural context</dt><dd>{data.structuralContext.join(' · ') || 'None reported'}</dd>
            </dl></details>
        </>}
    </aside>;
}
