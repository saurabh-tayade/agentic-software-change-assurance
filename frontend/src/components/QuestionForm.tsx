import type { CapabilitiesResponse, Strategy } from '../api/types';
import { ExampleQuestions } from './ExampleQuestions';
import { validQuestion } from '../hooks/useWorkspace';

interface Props {
    capabilities: CapabilitiesResponse | null; strategy: Strategy | null;
    ready: boolean; selectionMessage: string; question: string; onQuestion: (question: string) => void;
    pending: boolean; active: boolean; onSubmit: (strategy: Strategy, limit: number) => void;
}
export function QuestionForm({ capabilities, strategy, ready, selectionMessage, question, onQuestion, pending, active, onSubmit }: Props) {
    const limit = capabilities?.questionLengthLimit;
    const valid = limit !== undefined && validQuestion(question, limit);
    const canSubmit = ready && capabilities !== null && strategy !== null && capabilities.configuredRetrievalStrategies.includes(strategy) && valid && !pending;
    return <div className="question-form">
        <form onSubmit={event => { event.preventDefault(); if (canSubmit && strategy && limit !== undefined) onSubmit(strategy, limit); }}>
            <div className="ask-label-row"><label htmlFor="question">Ask</label><ExampleQuestions active={active} disabled={!ready || pending} onSelect={onQuestion} /></div>
            <textarea id="question" rows={2} value={question} onChange={event => onQuestion(event.target.value)}
                placeholder="Where is shipping cost calculated?" aria-describedby={`question-help question-length question-shortcut${question && limit !== undefined && !valid ? ' question-error' : ''}`}
                aria-keyshortcuts="Control+Enter Meta+Enter"
                aria-invalid={!!question && limit !== undefined && !valid} disabled={!ready || pending}
                onKeyDown={event => {
                    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && !event.nativeEvent.isComposing) {
                        event.preventDefault(); if (canSubmit && strategy && limit !== undefined) onSubmit(strategy, limit);
                    }
                }} />
            <div className="question-controls"><span id="question-help" className="question-guidance">{ready ? 'Ask about code or docs.' : selectionMessage}</span>
                <span id="question-length" className={limit !== undefined && question.length > limit ? 'over-limit' : ''}>
                    {question.length} / {limit ?? '…'}</span>
                <span id="question-shortcut" className="keyboard-hint">Ctrl / ⌘ + Enter</span>
                <button type="submit" className="primary" disabled={!canSubmit}>{pending ? 'Generating answer…' : 'Ask Question'}<span aria-hidden="true"> ↗</span></button></div>
            {question && limit !== undefined && !valid && <p id="question-error" className="validation-error" role="status">Use a nonblank question of at most {limit} UTF-16 characters, without invalid Unicode or NUL.</p>}
        </form>
    </div>;
}
