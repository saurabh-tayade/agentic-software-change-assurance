import { useSyncExternalStore } from 'react';
import { featureFor, routeHref, type RouteId } from './features';
const subscribe = (callback: () => void) => {
    window.addEventListener('hashchange', callback);
    return () => window.removeEventListener('hashchange', callback);
};
export function useRoute() {
    const hash = useSyncExternalStore(subscribe, () => window.location.hash);
    const [path, query] = hash.replace(/^#\/?/, '').split('?');
    const feature = featureFor(path ?? 'overview');
    const help = new URLSearchParams(query).get('help');
    return { feature, help, navigate: (id: RouteId, topic?: string) => { window.location.hash = routeHref(id, topic); } };
}
