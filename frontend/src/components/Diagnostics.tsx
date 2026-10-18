import type { AnswerResponse, EvidenceReference } from '../api/types';
import { shortId } from './Common';

export function Diagnostics({ answer, onEvidence }: { answer: AnswerResponse; onEvidence?: (evidence: EvidenceReference, key?: string) => void }) {
    return <details className="diagnostics"><summary>Retrieval & execution details</summary>
        <dl className="metadata diagnostics-metadata">
            <dt>Requested strategy</dt><dd>{answer.requestedRetrievalStrategy}</dd>
            <dt>Effective strategy</dt><dd className="mono">{answer.retrievalStrategy}</dd>
            <dt>Embedding profile</dt><dd className="mono">{answer.embeddingProfileId ?? 'Not used'}</dd>
            <dt>Total Q&A duration</dt><dd>{(answer.durationMillis / 1000).toFixed(2)} s</dd>
            <dt>Model</dt><dd>{answer.generationSettings.model}</dd>
            <dt>Prompt version</dt><dd>{answer.promptVersion}</dd>
            <dt>Prompt hash</dt><dd className="mono">{answer.promptHash}</dd>
            <dt>Schema hash</dt><dd className="mono">{answer.schemaHash}</dd>
            <dt>Answer version</dt><dd>{answer.answerVersion}</dd>
            <dt>Effective limits</dt><dd>topK {answer.configuration.topK} · {answer.configuration.maxEvidence} excerpts · {answer.configuration.maxContextBytes} context bytes · {answer.configuration.maxEvidenceBytes} bytes/excerpt</dd>
            <dt>Context selected</dt><dd>{answer.contextSelection.selected.length} excerpts · {answer.contextSelection.contextBytes} bytes · {answer.contextSelection.policy}</dd>
            <dt>Chat invocation</dt><dd>{answer.invocation ? `${answer.invocation.provider} · ${(answer.invocation.durationMillis / 1000).toFixed(2)} s` : 'No chat call was made'}</dd>
            {answer.invocation && <><dt>Input tokens</dt><dd>{answer.invocation.inputTokens ?? 'Unavailable'}</dd>
                <dt>Output tokens</dt><dd>{answer.invocation.outputTokens ?? 'Unavailable'}</dd>
                <dt>Finish reason</dt><dd>{answer.invocation.finishReason ?? 'Unavailable'}</dd></>}
        </dl>
        <h3>Ranked retrieved evidence</h3><p className="small muted">Scores express ranking, not answer confidence. Retrieved evidence may be omitted from the model context.</p>
        <ol className="retrieved-list">{answer.retrievedEvidence.map(item => <li key={`${item.rank}-${item.evidence.chunkId}`}>
            <span className="rank-number">{item.rank}</span><div>
                {onEvidence ? <button className="source-link" onClick={() => onEvidence(item.evidence)}>{item.evidence.path}:{item.evidence.startLine}–{item.evidence.endLine}</button>
                    : <code>{item.evidence.path}:{item.evidence.startLine}–{item.evidence.endLine}</code>}
                <p>Score {item.score.toPrecision(5)} · <code>{shortId(item.evidence.chunkId)}</code></p></div>
        </li>)}</ol>
        <h3>Context selection</h3><ul className="plain-list">{answer.contextSelection.selected.map(item => <li key={item.key}>
            {item.key} · retrieved rank {item.rank} · <code>{shortId(item.chunkId)}</code></li>)}</ul>
        <h3>Context omissions</h3>{answer.contextSelection.omissions.length ? <ul className="plain-list">{answer.contextSelection.omissions.map((item, index) =>
            <li key={index}>Rank {item.rank} · <code>{shortId(item.chunkId)}</code> · {item.reason}</li>)}</ul> : <p className="small muted">No context omissions reported.</p>}
        <h3>Retrieval & context limitations</h3><ul className="plain-list">{[...answer.retrievalLimitations, ...answer.contextSelection.limitations].map((text, i) => <li key={i}>{text}</li>)}</ul>
        <h3>Citation validation statement</h3><p className="validation-statement mono">{answer.validation}</p>
    </details>;
}
