import { useRef } from 'react';
import { useCatalog } from './hooks/useCatalog';
import { useWorkspace } from './hooks/useWorkspace';
import { IntelligenceWorkspace } from './IntelligenceWorkspace';
import { PlatformShell } from './platform/PlatformShell';
import { Overview, PlannedFeature } from './platform/Overview';
import { HelpCenter } from './help/HelpCenter';
import { HelpContext } from './help/HelpContext';
import type { HelpTopicId } from './help/topics';
import { RepositoriesPage } from './platform/RepositoriesPage';
import { useRoute } from './platform/useRoute';
export function App() {
    const catalogState = useCatalog();
    const workspace = useWorkspace();
    const route = useRoute();
    const helpOpener = useRef<HTMLElement | null>(null);
    const openHelp = (topic: HelpTopicId, opener?: HTMLElement | null) => {
        helpOpener.current = opener ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
        route.navigate(route.feature.id, topic);
    };
    return <HelpContext.Provider value={openHelp}><PlatformShell feature={route.feature} helpOpen={route.help !== null} onHelp={() => openHelp('getting-started')}>
        {route.feature.id === 'overview' && <Overview />}
        {route.feature.status === 'planned' && <PlannedFeature feature={route.feature} />}
        {route.feature.id === 'repositories' && <RepositoriesPage catalogState={catalogState} helpOpen={route.help !== null} {...(workspace.selected ? { selectedId: workspace.selected.publicationId } : {})}
            onExplore={repositoryId => { workspace.selectRepository(repositoryId); workspace.clearPublication(); route.navigate('intelligence'); }}
            onSelect={publication => { void workspace.selectPublication(publication); route.navigate('intelligence'); }} />}
        <div className="intelligence-host" hidden={route.feature.id !== 'intelligence'}><IntelligenceWorkspace catalogState={catalogState} workspace={workspace} active={route.feature.id === 'intelligence'} helpOpen={route.help !== null} /></div>
        <HelpCenter topic={route.help} route={route.feature.id} onClose={() => route.navigate(route.feature.id)}
            onRestoreFocus={() => {
                const opener = helpOpener.current;
                if (opener?.isConnected && !opener.closest('[hidden]') && !opener.closest('[role="dialog"]')) opener.focus();
                else document.getElementById('global-help')?.focus();
            }} />
    </PlatformShell></HelpContext.Provider>;
}
