import type { ComponentProps, ReactNode } from 'react';
import { DashboardHeader } from './DashboardHeader';

type Props = ComponentProps<typeof DashboardHeader> & { children: ReactNode; className?: string };

/** Keep dashboard actions outside the single, keyboard-scrollable content region. */
export function DashboardPage({ children, className = '', ...header }: Props) {
    return <div className={`platform-page dashboard-page ${className}`}>
        <DashboardHeader {...header} />
        <section className="dashboard-body" aria-label={`${header.title} content`} tabIndex={0}>
            {children}
        </section>
    </div>;
}
