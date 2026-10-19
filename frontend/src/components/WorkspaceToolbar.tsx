import type { CapabilitiesResponse, PublicationListResponse, PublicationReference, Strategy } from '../api/types';
import type { LoadState } from '../state';
import { useHelp } from '../help/HelpContext';
import { scopeLabel, shortId } from './Common';

const methods: Strategy[] = ['KEYWORD', 'VECTOR', 'HYBRID'];
const concise = (text: string) => text.length > 44 ? `${text.slice(0, 41)}…` : text;
export function versionLabel(publication: PublicationReference): string {
    const configuration = publication.processingConfiguration;
    return `${shortId(publication.commitSha)} · ${concise(scopeLabel(publication))} · ${configuration.maxChunks === 1 ? 'Single excerpt' : `${configuration.maxLines}-line excerpts`} · ${shortId(publication.publicationId)}`;
}
interface Props {
    catalog: LoadState<PublicationListResponse>; capabilities: LoadState<CapabilitiesResponse>;
    repositoryId: string; selected: PublicationReference | null; strategy: Strategy | null; pending: boolean;
    onRepository: (id: string) => void; onVersion: (publication: PublicationReference | null) => void;
    onStrategy: (strategy: Strategy) => void; onLoadPage: (after?: string | null) => void;
}
export function WorkspaceToolbar({ catalog, capabilities, repositoryId, selected, strategy, pending, onRepository, onVersion, onStrategy, onLoadPage }: Props) {
    const openHelp = useHelp();
    // Preserve an explicitly selected version if a catalog refresh only returns an earlier page.
    const publications = catalog.data?.items ?? [];
    const known = selected && !publications.some(item => item.publicationId === selected.publicationId) ? [...publications, selected] : publications;
    const repositories = [...new Set(known.map(item => item.repositoryId))];
    const versions = known.filter(item => item.repositoryId === repositoryId);
    return <section className="workspace-toolbar panel" aria-label="Workspace selection">
        <div className="toolbar-field repository-field"><label htmlFor="workspace-repository">Repository</label>
            <select id="workspace-repository" value={repositoryId} disabled={!repositories.length} onChange={event => onRepository(event.target.value)}>
                <option value="">{catalog.loading && !catalog.data ? 'Loading repositories…' : !repositories.length ? 'No repositories available' : 'Choose a repository'}</option>
                {repositoryId && !repositories.includes(repositoryId) && <option value={repositoryId}>{repositoryId}</option>}
                {repositories.map(id => <option key={id} value={id}>{id}</option>)}
            </select></div>
        <div className="toolbar-field version-field"><div className="toolbar-label"><label htmlFor="workspace-version">Version</label>
            <button className="info-button" type="button" aria-label="About repository versions" onClick={() => openHelp('versions')}>i</button></div>
            <select id="workspace-version" value={selected?.publicationId ?? ''} disabled={!repositoryId || !versions.length}
                aria-describedby="version-order" title={selected ? versionLabel(selected) : 'Select an explicit publication'}
                onChange={event => onVersion(versions.find(item => item.publicationId === event.target.value) ?? null)}>
                <option value="">Choose a version</option>
                {versions.map(item => <option key={item.publicationId} value={item.publicationId}>{versionLabel(item)}</option>)}
            </select><span className="sr-only" id="version-order">Versions differ by revision, scope, processing configuration, and publication identity. Order does not indicate recency.</span></div>
        <div className="toolbar-field search-field"><label htmlFor="workspace-search">Search Method</label>
            <div className="search-control">
            <select id="workspace-search" value={capabilities.data ? strategy ?? '' : ''} disabled={!capabilities.data || pending} aria-describedby="search-availability"
                onChange={event => { const value = methods.find(method => method === event.target.value); if (value) onStrategy(value); }}>
                {!capabilities.data && <option value="">{capabilities.loading ? 'Loading search methods…' : 'Search methods unavailable'}</option>}
                {methods.map(method => <option key={method} value={method} disabled={!capabilities.data?.configuredRetrievalStrategies.includes(method)}>
                    {method.charAt(0) + method.slice(1).toLowerCase()}{!capabilities.data ? ' (configuration unknown)' : !capabilities.data.configuredRetrievalStrategies.includes(method) ? ' (not configured)' : ''}
                </option>)}
            </select><button className="info-button search-help" type="button" aria-label="About search methods" onClick={() => openHelp('search-strategies')}>?</button></div><span id="search-availability" className="sr-only">{capabilities.data ? 'Configured does not guarantee publication index or model readiness.' : capabilities.loading ? 'Loading capabilities; strategy configuration is unknown.' : 'Capabilities request failed; strategy configuration is unknown.'}</span></div>
        {catalog.data?.nextAfter && <button className="text-button toolbar-more" disabled={catalog.loading} onClick={() => onLoadPage(catalog.data?.nextAfter)}>{catalog.loading ? 'Loading more…' : 'Load more publications'}</button>}
    </section>;
}
