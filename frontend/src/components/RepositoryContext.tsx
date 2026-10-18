import type { PublicationDetailResponse, PublicationListResponse, PublicationReference } from '../api/types';
import type { LoadState } from '../state';
import { EmptyState, ErrorNotice, PublicationProvenance, scopeLabel, shortId, StatusBadge } from './Common';

interface Props {
    catalog: LoadState<PublicationListResponse>; selected: PublicationReference | null;
    detail: LoadState<PublicationDetailResponse>; onSelect: (publication: PublicationReference) => void;
    onLoadPage: (after?: string | null) => void;
}

export function RepositoryContext({ catalog, selected, detail, onSelect, onLoadPage }: Props) {
    return <aside className="context-panel panel" aria-labelledby="context-title">
        <div className="panel-heading"><div><span className="eyebrow">01 / Context</span><h2 id="context-title">Repository publications</h2></div>
            <button className="icon-button" aria-label="Refresh publications" disabled={catalog.loading} onClick={() => onLoadPage()}>↻</button></div>
        <p className="muted small">Choose an immutable snapshot. Order does not indicate recency.</p>
        {catalog.loading && !catalog.data && <p role="status" className="loading">Loading publications…</p>}
        {catalog.error && <ErrorNotice error={catalog.error} action={<button className="text-button" onClick={() => onLoadPage()}>Retry discovery</button>} />}
        {catalog.data?.items.length === 0 && <EmptyState eyebrow="No publications" title="Nothing indexed here yet">
            <p>Prepare a publication with the existing CLI, then refresh this list.</p></EmptyState>}
        <ul className="publication-list" aria-label="Available publications">
            {catalog.data?.items.map(publication => <li key={publication.publicationId}>
                <button className={`publication-option ${selected?.publicationId === publication.publicationId ? 'selected' : ''}`}
                    aria-pressed={selected?.publicationId === publication.publicationId} onClick={() => onSelect(publication)}>
                    <span className="publication-name">{publication.repositoryId}<span aria-hidden="true">↗</span></span>
                    <span className="revision mono">{shortId(publication.commitSha)} <span className="muted">/ revision</span></span>
                    <span className="scope-label">{scopeLabel(publication)}</span>
                    <span className="small muted">{publication.processingConfiguration.maxLines} lines/chunk · {publication.processingConfiguration.maxChunks.toLocaleString()} chunk limit</span>
                    <span className="publication-bottom"><StatusBadge status={publication.processingStatus} /><code>{shortId(publication.publicationId)}</code></span>
                </button>
            </li>)}
        </ul>
        {catalog.data?.nextAfter && <button className="secondary full-width" disabled={catalog.loading}
            onClick={() => onLoadPage(catalog.data?.nextAfter)}>{catalog.loading ? 'Loading more…' : 'Load more publications'}</button>}
        {selected && <section className="context-details" aria-label="Selected publication details">
            <h3>Evidence coverage</h3>
            {detail.loading && <p role="status">Loading publication details…</p>}
            {detail.error && <ErrorNotice error={detail.error} action={<button className="text-button" onClick={() => onSelect(selected)}>Retry details</button>} />}
            {detail.data && <>
                <div className="coverage-grid">
                    <div><strong>{detail.data.coverage.ingested}</strong><span>files ingested</span></div>
                    <div><strong>{detail.data.coverage.chunks}</strong><span>evidence chunks</span></div>
                </div>
                <p className="small muted">{detail.data.coverage.parsed} parsed · {detail.data.coverage.excluded} excluded · {detail.data.coverage.unsupported} unsupported · {detail.data.coverage.failed} ingestion failures</p>
                {detail.data.publication.processingStatus !== 'COMPLETE' && <div className="notice warning">
                    <strong>{detail.data.publication.processingStatus === 'PARTIAL' ? 'Partial evidence coverage' : 'Processing failed'}</strong>
                    <p>This is the saved processing state. Answers may be limited by the available evidence.</p></div>}
                <details><summary>Provenance & coverage details</summary>
                    <PublicationProvenance publication={detail.data.publication} />
                    <h3>Limitations</h3><ul className="plain-list">{[...detail.data.ingestionLimitations, ...detail.data.processingLimitations].map((text, i) => <li key={i}>{text}</li>)}</ul>
                    {detail.data.artifacts.map(artifact => <div className="artifact" key={artifact.path}>
                        <code>{artifact.path}</code><p className="small">{artifact.status.toLowerCase()} · {artifact.chunkCount} chunks</p>
                        {artifact.limitations.map((item, i) => <p className="small" key={i}>{item.reason}: {item.detail}</p>)}</div>)}
                    {detail.data.ingestionCoverage.map((item, i) => <p className="small" key={i}><code>{item.path}</code> · {item.disposition} · {item.reason}: {item.detail}</p>)}
                </details>
            </>}
        </section>}
        <div className="context-footnote"><span aria-hidden="true">◈</span> Committed source. Explicit revision.<br />Read-only access to local publications.</div>
    </aside>;
}
