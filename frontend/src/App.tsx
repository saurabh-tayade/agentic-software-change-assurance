import { RepositoryContext } from './components/RepositoryContext';
import { EmptyState, ErrorNotice } from './components/Common';
import { useCatalog } from './hooks/useCatalog';
import { useWorkspace } from './hooks/useWorkspace';
import { QuestionForm } from './components/QuestionForm';
import { AnswerPanel } from './components/AnswerPanel';
import { EvidenceExplorer } from './components/EvidenceExplorer';

export function App() {
    const { capabilities, catalog, loadPage, loadCapabilities } = useCatalog();
    const workspace = useWorkspace();
    return <>
        <a className="skip-link" href="#question-workspace">Skip to question workspace</a>
        <header className="app-header"><div className="brand"><span className="brand-mark" aria-hidden="true">⌘</span>
            <span>Change Assurance<span className="brand-divider">/</span><strong>Repository Intelligence</strong></span></div>
            <span className="local-badge"><span className="status-dot" />Local · read-only</span></header>
        <div className="page-heading"><div><p className="eyebrow">Repository intelligence workspace</p>
            <h1>Understand the code. Inspect the evidence.</h1><p className="muted">Grounded answers, explicit revisions, original source.</p></div>
            <div className="workflow-hint"><span>01 Select</span><b>→</b><span>02 Ask</span><b>→</b><span>03 Inspect</span></div></div>
        <main className="workspace">
            <RepositoryContext catalog={catalog} detail={workspace.detail} selected={workspace.selected}
                onSelect={publication => void workspace.selectPublication(publication)} onLoadPage={after => void loadPage(after)} />
            <section className="question-panel" id="question-workspace" aria-label="Question workspace" tabIndex={-1}>
                <p className="sr-only" role="status">{workspace.answer.data ? `Answer ready: ${workspace.answer.data.status.replaceAll('_', ' ').toLowerCase()}. Review claims and uncertainty below.` : ''}</p>
                {capabilities.loading && <p role="status">Loading retrieval capabilities…</p>}
                {capabilities.error && <ErrorNotice error={capabilities.error} action={<button className="text-button" onClick={() => void loadCapabilities()}>Retry capabilities</button>} />}
                <QuestionForm capabilities={capabilities.data} capabilitiesLoading={capabilities.loading} publication={workspace.selected} ready={!!workspace.detail.data}
                    question={workspace.question} onQuestion={workspace.setQuestion} pending={workspace.answer.loading}
                    onSubmit={(strategy, limit) => void workspace.submit(strategy, limit)} />
                {workspace.answer.loading && <div className="panel processing" role="status"><span className="processing-mark" aria-hidden="true">◌</span><div>
                    <strong>Retrieving evidence and preparing a grounded answer</strong><p>Local inference can take a while. This request will not be retried automatically.</p></div></div>}
                {workspace.answer.error && <ErrorNotice error={workspace.answer.error} />}
                {workspace.answer.data && <AnswerPanel answer={workspace.answer.data} onEvidence={(reference, key) => void workspace.openEvidence(reference, key)} />}
                {!workspace.answer.data && !workspace.answer.loading && !workspace.answer.error && <div className="panel"><EmptyState eyebrow="Evidence before conclusions" title={workspace.selected ? 'Your next question starts here' : 'Begin with a publication'}>
                    <p>{workspace.selected ? 'Ask about behavior, implementation, or documented decisions. Inspect the source behind each cited claim.' : 'Select a repository publication to set the evidence boundary for your question.'}</p>
                </EmptyState></div>}
            </section>
            <EvidenceExplorer state={workspace.evidence} citation={workspace.citation}
                onRetry={() => { if (workspace.citation) void workspace.openEvidence(workspace.citation.reference, workspace.citation.key); }} />
        </main>
        <footer className="app-footer">Citation identity can be checked. Factual correctness still requires review.<span>Repository Intelligence · M1.9</span></footer>
    </>;
}
