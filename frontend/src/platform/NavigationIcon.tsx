import type { ReactNode } from 'react';
import type { RouteId } from './features';

const paths: Record<RouteId | 'production-ai', ReactNode> = {
    overview: <><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></>,
    repositories: <><path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" /><path d="M3 9h18" /></>,
    intelligence: <><circle cx="10" cy="10" r="7" /><path d="m15 15 6 6M8 8a2 2 0 0 1 4 0c0 2-2 1-2 3M10 14h.01" /></>,
    contracts: <><path d="M14 2H5v20h14V7l-5-5ZM14 2v6h5M8 12h8M8 16h6" /></>,
    impact: <><rect x="9" y="2" width="6" height="6" rx="1" /><rect x="2" y="16" width="6" height="6" rx="1" /><rect x="16" y="16" width="6" height="6" rx="1" /><path d="M12 8v4H5v4M12 12h7v4" /></>,
    'production-ai': <><path d="M3 3v18h18M7 16v-4M12 16V7M17 16v-7" /></>,
    verification: <><path d="m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6l9-4Z" /><path d="m8 12 3 3 5-6" /></>,
};
/** Decorative: the enclosing link supplies the accessible destination name. */
export function NavigationIcon({ destination }: { destination: RouteId | 'production-ai' }) {
    return <svg className="navigation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{paths[destination]}</svg>;
}
