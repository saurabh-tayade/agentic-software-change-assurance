import type { PublicationDetailResponse } from '../api/types';
import { ContextHelp } from '../help/ContextHelp';
import { PublicationProvenance, StatusBadge, scopeLabel } from './Common';
export function PublicationDetails({ detail }: { detail: PublicationDetailResponse }) {
    const { publication, coverage } = detail;
    return <section className="publication-details" aria-label="Selected publication details">
        <div className="section-heading"><h3>{publication.repositoryId}</h3><StatusBadge status={publication.processingStatus} /></div>
        <dl className="metadata publication-identity"><dt>Git revision</dt><dd className="mono">{publication.commitSha}</dd>
            <dt>Publication</dt><dd className="mono">{publication.publicationId}</dd><dt>Scope</dt><dd>{scopeLabel(publication)}</dd></dl>
        <h3>Evidence coverage <ContextHelp about="coverage" /></h3>
        <div className="coverage-grid"><div><strong>{coverage.ingested}</strong><span>files ingested</span></div><div><strong>{coverage.chunks}</strong><span>source excerpts</span></div></div>
        <p className="small muted">{coverage.discovered} discovered · {coverage.parsed} parsed · {coverage.excluded} excluded · {coverage.unsupported} unsupported · {coverage.failed} ingestion failures</p>
        <p className="small">Scope describes selected paths, not guaranteed evidence completeness.</p>
        {publication.processingStatus !== 'COMPLETE' && <div className="notice warning"><strong>{publication.processingStatus === 'PARTIAL' ? 'Partial evidence coverage' : 'Processing failed'}</strong>
            <p>This is the saved processing state. Some source material may be unavailable; PARTIAL is not an API request failure.</p></div>}
        <details><summary>Processing configuration & provenance</summary><p className="small">{publication.processingConfiguration.maxLines} lines/chunk · {publication.processingConfiguration.maxChunks.toLocaleString()} chunk limit</p><PublicationProvenance publication={publication} /></details>
        <details><summary>Ingestion & processing limitations ({detail.ingestionLimitations.length + detail.processingLimitations.length})</summary>
            <h3>Ingestion</h3>{detail.ingestionLimitations.length ? <ul className="plain-list">{detail.ingestionLimitations.map((item, i) => <li key={i}>{item}</li>)}</ul> : <p className="small">None reported.</p>}
            <h3>Processing</h3>{detail.processingLimitations.length ? <ul className="plain-list">{detail.processingLimitations.map((item, i) => <li key={i}>{item}</li>)}</ul> : <p className="small">None reported.</p>}</details>
        <details><summary>File coverage & processing details</summary>
            <p className="small">{coverage.artifacts} artifacts · {coverage.completeArtifacts} complete · {coverage.partialArtifacts} partial · {coverage.failedArtifacts} failed</p>
            {detail.artifacts.map(artifact => <div className="artifact" key={artifact.path}><code>{artifact.path}</code><p className="small">{artifact.status} · {artifact.chunkCount} excerpts · {artifact.parser}</p>
                {artifact.limitations.map((item, i) => <p className="small" key={i}>{item.reason}: {item.detail}</p>)}</div>)}
            {detail.ingestionCoverage.map((item, i) => <p className="small" key={i}><code>{item.path}</code> · {item.disposition} · {item.reason}: {item.detail}</p>)}
        </details>
    </section>;
}
