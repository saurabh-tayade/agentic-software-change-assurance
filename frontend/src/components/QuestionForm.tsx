import { useState } from 'react';
import type { CapabilitiesResponse, PublicationReference, Strategy } from '../api/types';
import { validQuestion } from '../hooks/useWorkspace';
import { shortId } from './Common';

interface Props {
    capabilities: CapabilitiesResponse | null; publication: PublicationReference | null;
    ready: boolean; question: string; onQuestion: (question: string) => void;
    pending: boolean; onSubmit: (strategy: Strategy, limit: number) => void;
}
const strategies: Strategy[] = ['KEYWORD', 'VECTOR', 'HYBRID'];
const descriptions: Record<Strategy, string> = {
    KEYWORD: 'Find evidence by matching words and phrases.',
    VECTOR: 'Find evidence by semantic similarity.',
    HYBRID: 'Combine keyword and semantic rankings.',
};

export function QuestionForm({ capabilities, publication, ready, question, onQuestion, pending, onSubmit }: Props) {
    const [chosen, setChosen] = useState<Strategy | null>(null);
    const strategy = chosen && capabilities?.configuredRetrievalStrategies.includes(chosen)
        ? chosen : capabilities?.defaultRetrievalStrategy ?? 'KEYWORD';
    const limit = capabilities?.questionLengthLimit;
    const valid = limit !== undefined && validQuestion(question, limit);
    const canSubmit = ready && capabilities !== null && capabilities.configuredRetrievalStrategies.includes(strategy) && valid && !pending;
    return <section className="panel question-form" aria-labelledby="question-title">
        <div className="panel-heading"><div><span className="eyebrow">02 / Investigate</span><h2 id="question-title">Ask about this repository</h2></div>
            <span className="query-symbol" aria-hidden="true">⌕</span></div>
        <div className="question-context">{publication ? <><span className="context-indicator" aria-hidden="true" />
            <strong>{publication.repositoryId}</strong><code>{shortId(publication.commitSha)}</code></> : 'Select a publication to define the evidence boundary.'}</div>
        <form onSubmit={event => { event.preventDefault(); if (canSubmit && limit !== undefined) onSubmit(strategy, limit); }}>
            <label htmlFor="question">Repository question</label>
            <textarea id="question" rows={4} value={question} onChange={event => onQuestion(event.target.value)}
                placeholder="Where is shipping cost calculated?" aria-describedby="question-help question-length"
                aria-invalid={!!question && limit !== undefined && !valid} disabled={pending}
                onKeyDown={event => {
                    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && !event.nativeEvent.isComposing) {
                        event.preventDefault(); if (canSubmit && limit !== undefined) onSubmit(strategy, limit);
                    }
                }} />
            <div className="question-help"><span id="question-help">Ask a specific question about committed code or documentation.</span>
                <span id="question-length" className={limit !== undefined && question.length > limit ? 'over-limit' : ''}>
                    {question.length} / {limit ?? '…'}</span></div>
            {question && limit !== undefined && !valid && <p className="validation-error" role="status">Use a nonblank question of at most {limit} UTF-16 characters, without invalid Unicode or NUL.</p>}
            <fieldset className="strategy-fieldset" disabled={pending || !capabilities}><legend>Retrieval strategy</legend>
                <div className="strategy-options">{strategies.map(option => <label key={option} className={`strategy-option ${strategy === option ? 'active' : ''}`}>
                    <input type="radio" name="strategy" value={option} checked={strategy === option}
                        disabled={!capabilities?.configuredRetrievalStrategies.includes(option)} onChange={() => setChosen(option)} />
                    {option.charAt(0) + option.slice(1).toLowerCase()}
                    {!capabilities?.configuredRetrievalStrategies.includes(option) && <span className="sr-only"> (not configured)</span>}
                </label>)}</div>
            </fieldset>
            <p className="strategy-help">{descriptions[strategy]}<br />Configured does not guarantee index or model readiness.</p>
            <div className="submit-row"><span className="keyboard-hint">Ctrl / ⌘ + Enter</span>
                <button type="submit" className="primary" disabled={!canSubmit}>{pending ? 'Generating answer…' : 'Ask repository'}<span aria-hidden="true"> ↗</span></button></div>
        </form>
    </section>;
}
