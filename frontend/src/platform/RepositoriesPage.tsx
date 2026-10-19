import { useEffect, useId, useRef, useState } from 'react';
import { Modal } from './Modal';
import { DashboardPage } from './DashboardPage';
import { NavigationIcon } from './NavigationIcon';
import type { PublicationReference } from '../api/types';
import type { useCatalog } from '../hooks/useCatalog';
import { ErrorNotice, EmptyState } from '../components/Common';
import { PublicationChoice, repositoryName } from '../components/PublicationChoice';
export function RepositoriesPage({ catalogState, selectedId, onSelect, onExplore, helpOpen }: {
    helpOpen: boolean; catalogState: ReturnType<typeof useCatalog>; selectedId?: string; onSelect: (publication: PublicationReference) => void; onExplore: (repositoryId: string) => void;
}) {
    const { catalog, loadPage } = catalogState;
    const [connectionOpen, setConnectionOpen] = useState(false);
    const connectionLauncher = useRef<HTMLButtonElement>(null);
    useEffect(() => { if (helpOpen) setConnectionOpen(false); }, [helpOpen]);
    const groups = new Map<string, PublicationReference[]>();
    for (const publication of catalog.data?.items ?? []) groups.set(publication.repositoryId, [...(groups.get(publication.repositoryId) ?? []), publication]);
    return <DashboardPage className="repositories-page" title="Repositories" description="Browse prepared repository versions and explore their source evidence."
            actions={<><button className="secondary" onClick={() => void loadPage()} disabled={catalog.loading}>Refresh Catalog</button>
                <button ref={connectionLauncher} className="secondary connection-launcher" onClick={() => setConnectionOpen(true)}
                    aria-haspopup="dialog">Connect Repository <span className="badge amber">Planned</span></button></>}>
        {catalog.loading && <p role="status">Loading repositories…</p>}
        {catalog.error && <ErrorNotice error={catalog.error} action={<button className="text-button" onClick={() => void loadPage()}>Retry discovery</button>} />}
        {catalog.data?.items.length === 0 && <EmptyState eyebrow="No prepared versions" title="No repositories available"><p>Prepare a publication with the existing CLI, then refresh this catalog.</p></EmptyState>}
        <div className="dashboard-grid repository-groups">{[...groups].map(([id, publications]) => <RepositoryTile key={id} id={id}
            publications={publications} selectedId={selectedId} hasMore={!!catalog.data?.nextAfter} onSelect={onSelect} onExplore={onExplore} />)}</div>
        {catalog.data?.nextAfter && <button className="secondary" disabled={catalog.loading} onClick={() => void loadPage(catalog.data?.nextAfter)}>Load more publications</button>}
        <Modal open={connectionOpen && !helpOpen} onOpenChange={setConnectionOpen} title="Connect a Repository"
            description="Repository connections will allow users to prepare local Git or supported remote repositories for analysis."
            className="connection-dialog" onRestoreFocus={() => {
                if (!helpOpen) connectionLauncher.current?.focus();
            }}>
            <ConnectionPreview />
        </Modal>
    </DashboardPage>;
}
function RepositoryTile({ id, publications, selectedId, hasMore, onSelect, onExplore }: {
    id: string; publications: PublicationReference[]; selectedId: string | undefined; hasMore: boolean;
    onSelect: (publication: PublicationReference) => void; onExplore: (repositoryId: string) => void;
}) {
    const [expanded, setExpanded] = useState(false);
    const versionsId = useId();
    const failed = publications.some(publication => publication.processingStatus === 'FAILED');
    const partial = publications.some(publication => publication.processingStatus === 'PARTIAL');
    return <section className="dashboard-tile repository-group" aria-label={`Repository ${id}`}>
        <div className="tile-heading"><NavigationIcon destination="repositories" /><h3>{repositoryName(id)}</h3></div>
        <p className="repository-id small muted">Repository: <code>{id}</code></p>
        <p className="small">{hasMore ? 'Loaded publications' : 'Available publications'}: {publications.length}</p>
        <span className={`badge ${failed ? 'red' : partial ? 'amber' : 'green'}`}>{failed ? 'Includes failed processing' : partial ? 'Includes partial coverage' : 'Prepared evidence'}</span>
        <div className="tile-actions"><button className="secondary" aria-expanded={expanded} aria-controls={versionsId} onClick={() => setExpanded(value => !value)}>Inspect versions ({publications.length})</button>
            <button className="text-button" aria-label={`Explore ${id}`} onClick={() => onExplore(id)}>Explore <span aria-hidden="true">→</span></button></div>
        <div id={versionsId} className="repository-versions" hidden={!expanded}>
            <p className="small muted">A publication is an immutable version with a pinned revision, scope, and processing settings. Order does not indicate recency. Choose a version explicitly to explore it.</p>
            <p className="small muted">Source origin not recorded. Scope does not guarantee complete evidence coverage. After opening a version, use Details for file coverage and processing limitations.</p>
            <div className="catalog-versions">{publications.map((publication, index) => <PublicationChoice key={publication.publicationId}
                publication={publication} number={index + 1} selected={selectedId === publication.publicationId} onSelect={onSelect} />)}</div>
        </div>
    </section>;
}
function ConnectionPreview() {
    return <div className="connection-preview"><span className="badge amber">Planned — Not available in v0.1</span>
        <p>For v0.1, repositories are prepared using the existing ingestion CLI.</p>
        <p className="small muted">Illustrative preview only. Do not enter credentials. Nothing is uploaded, cloned, or loaded.</p>
        <fieldset disabled><legend>Future source options</legend><div className="connection-options"><label><input type="radio" name="future-source" defaultChecked />Remote Git repository</label>
            <label><input type="radio" name="future-source" />Local repository/folder</label></div>
            <p className="small muted">Git hosting examples: GitHub, GitLab, Bitbucket. Other version-control systems would require future adapters.</p>
            <div className="connection-fields"><label>Repository URL<input type="text" placeholder="https://git.example.com/team/project.git" /></label>
                <label>Revision / branch / tag<input type="text" placeholder="Choose a revision" /></label>
                <label>Source selection<select defaultValue="all"><option value="all">All files — illustrative</option></select></label></div>
            <button className="primary" disabled>Load Repository</button>
        </fieldset>
    </div>;
}
