import { useId, type ReactNode } from 'react';

interface Props {
    title: string;
    description: string;
    actions?: ReactNode;
    compact?: boolean;
}

/** The platform shell supplies h1; dashboard content starts at h2. */
export function DashboardHeader({ title, description, actions, compact = false }: Props) {
    const titleId = useId();
    return <header className={`dashboard-header${compact ? ' dashboard-header-compact' : ''}`} aria-labelledby={titleId}>
        <div className="dashboard-heading"><h2 id={titleId}>{title}</h2><p>{description}</p></div>
        {actions && <div className="dashboard-header-actions">{actions}</div>}
    </header>;
}
