import { useEffect, useRef, useState } from 'react';
import { Modal } from './platform/Modal';
import type { EvidenceReference, Strategy } from './api/types';
import { ErrorNotice, shortId, StatusBadge } from './components/Common';
import type { useCatalog } from './hooks/useCatalog';
import type { useWorkspace } from './hooks/useWorkspace';
import { QuestionForm } from './components/QuestionForm';
import { AnswerPanel } from './components/AnswerPanel';
import { WorkspaceToolbar } from './components/WorkspaceToolbar';
import { PublicationDetails } from './components/PublicationDetails';
import { SupportingSourcesDrawer } from './components/SupportingSourcesDrawer';

export function IntelligenceWorkspace({ catalogState, workspace, active, helpOpen }: { active: boolean; helpOpen: boolean; catalogState: ReturnType<typeof useCatalog>; workspace: ReturnType<typeof useWorkspace> }) {
    const { capabilities, catalog, loadPage, loadCapabilities } = catalogState;
    const [chosenStrategy, setChosenStrategy] = useState<Strategy | null>(null);
    const strategy = chosenStrategy ?? capabilities.data?.defaultRetrievalStrategy ?? null;
    const [fullOpen, setFullOpen] = useState(false);
    const [detailsOpen, setDetailsOpen] = useState(false);
    const [drawerOpen, setDrawerOpen] = useState(false);
    const returnToSource = useRef(false);
    const fullButton = useRef<HTMLButtonElement>(null);
    const detailsButton = useRef<HTMLButtonElement>(null);
    const sourcesButton = useRef<HTMLButtonElement>(null);
    useEffect(() => { setFullOpen(false); }, [workspace.answer.data]);
    useEffect(() => { setDrawerOpen(false); setDetailsOpen(false); }, [workspace.selected?.publicationId]);
    useEffect(() => {
        if (!active || helpOpen) { setFullOpen(false); setDetailsOpen(false); }
        if (helpOpen) setDrawerOpen(false);
    }, [active, helpOpen]);
    const openSource = (reference: EvidenceReference, key?: string) => {
        if (reference.publicationId !== workspace.selected?.publicationId) return;
        if (fullOpen) { returnToSource.current = true; setFullOpen(false); }
        setDetailsOpen(false); setDrawerOpen(true);
        void workspace.openEvidence(reference, key);
    };
    const restoreSourcesFocus = () => {
        if (helpOpen) return;
        if (!active) { document.getElementById('main-content')?.focus(); return; }
        sourcesButton.current?.focus();
    };
    const selected = workspace.selected;
    return <div className="intelligence-workspace">
        <WorkspaceToolbar catalog={catalog} capabilities={capabilities} repositoryId={workspace.repositoryId} selected={selected}
            strategy={strategy} pending={workspace.answer.loading} onStrategy={setChosenStrategy}
            onRepository={workspace.selectRepository} onVersion={publication => { if (publication) void workspace.selectPublication(publication); else workspace.clearPublication(); }}
            onLoadPage={after => void loadPage(after)} />
        <section className="panel explore-panel" id="question-workspace" aria-labelledby="explore-title" tabIndex={-1}>
            <button ref={sourcesButton} className="sources-tab" aria-label="Supporting Sources" aria-haspopup="dialog" aria-expanded={drawerOpen && active && !helpOpen}
                onClick={() => setDrawerOpen(!drawerOpen)}><span aria-hidden="true">‹</span><span>Supporting Sources</span></button>
            <header className="explore-header"><div className="explore-heading"><h2 id="explore-title">Explore</h2></div>
            <div className="repository-summary" role="group" aria-label="Question publication">{selected ? <>
                <span>Repository: <strong>{selected.repositoryId}</strong></span><span>Revision: <code>{shortId(selected.commitSha)}</code></span><span>Publication: <code>{shortId(selected.publicationId)}</code></span>
                <StatusBadge status={selected.processingStatus} />
                <button ref={detailsButton} className="text-button" disabled={!workspace.detail.data} onClick={() => { setFullOpen(false); setDetailsOpen(true); }}>Details</button>
            </> : <p className="small muted">Select the repository and immutable version to explore.</p>}</div>
            </header>
            <div className="explore-body">
                <p className="sr-only" role="status">{workspace.answer.data ? `Answer ready: ${workspace.answer.data.status.replaceAll('_', ' ').toLowerCase()}. Open full answer for claims and uncertainty.` : ''}</p>
                {catalog.loading && <p role="status">Loading publications…</p>}
                {catalog.error && <ErrorNotice error={catalog.error} action={<button className="text-button" onClick={() => void loadPage()}>Retry discovery</button>} />}
                {catalog.data?.items.length === 0 && <div className="notice">No repositories available. Prepare a publication with the existing CLI, then reload this page.</div>}
                {capabilities.loading && <p role="status">Loading retrieval capabilities…</p>}
                {capabilities.error && <ErrorNotice error={capabilities.error} action={<button className="text-button" onClick={() => void loadCapabilities()}>Retry capabilities</button>} />}
                {workspace.detail.loading && <p role="status">Loading publication details…</p>}
                {workspace.detail.error && selected && <ErrorNotice error={workspace.detail.error} action={<button className="text-button" onClick={() => void workspace.selectPublication(selected)}>Retry details</button>} />}
                <QuestionForm capabilities={capabilities.data} strategy={strategy} ready={!!workspace.detail.data} selectionMessage={selected ? workspace.detail.loading ? 'Loading the selected version…' : 'Load valid publication details to ask a question.' : 'Choose a repository and version to ask a question.'}
                    question={workspace.question} onQuestion={workspace.setQuestion} pending={workspace.answer.loading} active={active && !helpOpen && !fullOpen && !detailsOpen && !drawerOpen}
                    onSubmit={(method, limit) => void workspace.submit(method, limit)} />
                {workspace.answer.loading && <div className="processing notice" role="status"><span className="processing-mark" aria-hidden="true">◌</span><div>
                    <strong>Retrieving evidence and preparing a grounded answer</strong><p>Local inference can take a while. This request will not be retried automatically.</p></div></div>}
                {workspace.answer.error && <ErrorNotice error={workspace.answer.error} />}
                {workspace.answer.data && <>
                    <AnswerPanel answer={workspace.answer.data} preview draftChanged={workspace.question !== workspace.answer.data.question}
                        onEvidence={openSource} fullAnswerRef={fullButton} onFullAnswer={() => { returnToSource.current = false; setDetailsOpen(false); setDrawerOpen(false); setFullOpen(true); }} />
                    <Modal open={fullOpen && active && !helpOpen} onOpenChange={setFullOpen} title="Full Answer" description="Complete original answer, claims, supporting sources, and uncertainty. Opening a citation returns to Supporting Sources."
                        onRestoreFocus={() => {
                            if (helpOpen) return;
                            if (!active) { document.getElementById('main-content')?.focus(); return; }
                            if (returnToSource.current) document.getElementById('evidence-title')?.focus(); else fullButton.current?.focus();
                        }}>
                        <AnswerPanel answer={workspace.answer.data} onEvidence={openSource} />
                    </Modal>
                </>}
                {!workspace.answer.data && !workspace.answer.loading && !workspace.answer.error && <p className="explore-empty small muted">Ask about the repository to see an answer and inspect its supporting sources here.</p>}
            </div>
        </section>
        <SupportingSourcesDrawer open={drawerOpen && active && !helpOpen && !fullOpen && !detailsOpen}
            onClose={() => setDrawerOpen(false)} restoreFocus={restoreSourcesFocus} state={workspace.evidence} citation={workspace.citation}
            onRetry={() => { if (workspace.citation) void workspace.openEvidence(workspace.citation.reference, workspace.citation.key); }} />
        {workspace.detail.data && <Modal open={detailsOpen && active && !helpOpen} onOpenChange={setDetailsOpen} title="Publication Details"
            description="Immutable repository context, processing status, and evidence coverage. Scope does not guarantee complete evidence."
            onRestoreFocus={() => { if (helpOpen) return; if (active) detailsButton.current?.focus(); else document.getElementById('main-content')?.focus(); }}>
            <PublicationDetails detail={workspace.detail.data} />
        </Modal>}
    </div>;
}
