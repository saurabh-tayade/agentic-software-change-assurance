import type { AnswerResponse, EvidenceReference } from '../api/types';
import { Diagnostics } from './Diagnostics';
import { shortId, StatusBadge } from './Common';

interface Props { answer: AnswerResponse; onEvidence?: (evidence: EvidenceReference, key?: string) => void }
const meanings = {
    SUPPORTED: 'The answer reports support from the selected evidence. Its factual correctness has not been independently verified.',
    PARTIALLY_SUPPORTED: 'Some conclusions have evidence support; other parts remain uncertain.',
    INSUFFICIENT_EVIDENCE: 'The selected evidence is not sufficient to answer this question. This does not establish that the functionality is absent.',
};

export function AnswerPanel({ answer, onEvidence }: Props) {
    const publication = answer.publication.publication;
    const policies = [...answer.publication.ingestionLimitations, ...answer.publication.processingLimitations];
    const coverage = [
        ...(publication.processingStatus !== 'COMPLETE' ? [`${publication.processingStatus} processing; evidence coverage is incomplete.`] : []),
        ...(answer.contextSelection.omissions.length ? [`${answer.contextSelection.omissions.length} retrieved excerpt(s) omitted from the model context.`] : []),
    ];
    return <article className="panel answer-panel" aria-labelledby="answer-title">
        <div className="panel-heading"><div><span className="eyebrow">Grounded response</span><h2 id="answer-title">Answer & supporting evidence</h2></div>
            <StatusBadge status={answer.status} /></div>
        <div className="answer-context"><span>{answer.requestedRetrievalStrategy}</span><span>revision <code>{shortId(publication.commitSha)}</code></span>
            <span>publication <code>{shortId(publication.publicationId)}</code></span></div>
        <p className="asked-question">{answer.question}</p>
        <p className="answer-text">{answer.answer}</p>
        <p className="status-meaning">{meanings[answer.status]}</p>
        {answer.claims.length > 0 && <section aria-label="Structured claims"><h3 className="section-label">Claims & citations</h3>
            <ol className="claims">{answer.claims.map((claim, index) => <li key={index}>
                <span className="claim-number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span><div><p>{claim.text}</p>
                    <div className="citation-buttons">{claim.evidenceKeys.map(key => {
                        const citation = answer.citations.find(item => item.key === key);
                        return citation ? <button className="citation-button" key={key} disabled={!onEvidence}
                            onClick={() => onEvidence?.(citation.evidence, key)} aria-label={`Open citation ${key}: ${citation.evidence.path} lines ${citation.evidence.startLine}–${citation.evidence.endLine}`}>
                            <strong>{key}</strong><span>{citation.evidence.path.split('/').at(-1)}:{citation.evidence.startLine}–{citation.evidence.endLine}</span><span aria-hidden="true">↗</span>
                        </button> : <span className="small muted" key={key}>{key} · reference unavailable</span>;
                    })}</div></div></li>)}</ol></section>}
        {answer.citations.filter(c => !answer.claims.some(claim => claim.evidenceKeys.includes(c.key))).map(citation =>
            <button className="citation-button" key={citation.key} disabled={!onEvidence} onClick={() => onEvidence?.(citation.evidence, citation.key)}>
                Open citation {citation.key}: {citation.evidence.path}</button>)}
        <section className="uncertainty-section" aria-label="Uncertainty and limitations"><h3>What remains uncertain</h3>
            {answer.uncertainties.length ? <ul className="plain-list">{answer.uncertainties.map((text, i) => <li key={i}>{text}</li>)}</ul>
                : <p className="small">No additional uncertainties were reported. This is not a guarantee of correctness.</p>}
            {coverage.length > 0 && <p className="small"><strong>Evidence coverage: </strong>{coverage.join(' ')}</p>}
            {policies.length > 0 && <details className="general-limitations"><summary>General ingestion & processing limitations ({policies.length})</summary>
                <ul className="plain-list">{policies.map((text, i) => <li key={i}>{text}</li>)}</ul>
            </details>}
        </section>
        <div className="identity-note"><span aria-hidden="true">◈</span><p><strong>{answer.citations.length ? 'Citation identities validated' : 'No citations returned.'}</strong><br />
            {answer.citations.length ? 'Source identity checked; semantic support not independently verified.' : 'Factual correctness has not been independently verified.'}</p></div>
        <Diagnostics answer={answer} {...(onEvidence ? { onEvidence } : {})} />
    </article>;
}
