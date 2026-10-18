import type { ReactNode } from 'react';
import type { ApiError } from '../api/client';
import type { PublicationReference } from '../api/types';

export function ErrorNotice({ error, action }: { error: ApiError; action?: ReactNode }) {
    return <div className="notice error" role="alert"><strong>Unable to complete this operation</strong>
        <p>{error.message}</p><code className="error-code">{error.code}</code>{action}</div>;
}
export function StatusBadge({ status }: { status: string }) {
    const tone = status === 'COMPLETE' || status === 'SUPPORTED' ? 'green' : status === 'FAILED' ? 'red' : 'amber';
    return <span className={`badge ${tone}`}><span aria-hidden="true" className="status-dot" />{status.replaceAll('_', ' ').toLowerCase()}</span>;
}
export function EmptyState({ eyebrow, title, children }: { eyebrow: string; title: string; children: ReactNode }) {
    return <div className="empty-state"><span className="empty-symbol" aria-hidden="true">⌘</span>
        <p className="eyebrow">{eyebrow}</p><h3>{title}</h3><div>{children}</div></div>;
}
export const shortId = (id: string): string => id.slice(0, 10);
export function scopeLabel(publication: PublicationReference): string {
    const { includes, excludes } = publication.scope;
    return `${includes.length ? includes.join(', ') : 'All paths'}${excludes.length ? ` · excludes ${excludes.join(', ')}` : ''}`;
}
export function PublicationProvenance({ publication }: { publication: PublicationReference }) {
    return <dl className="metadata">
        <dt>Repository</dt><dd>{publication.repositoryId}</dd>
        <dt>Git revision</dt><dd className="mono">{publication.commitSha}</dd>
        <dt>Publication</dt><dd className="mono">{publication.publicationId}</dd>
        <dt>Snapshot</dt><dd className="mono">{publication.snapshotId}</dd>
        <dt>Derivation</dt><dd className="mono">{publication.derivationId}</dd>
        <dt>Include paths</dt><dd>{publication.scope.includes.join(', ') || 'All paths'}</dd>
        <dt>Exclude paths</dt><dd>{publication.scope.excludes.join(', ') || 'None'}</dd>
        <dt>Processing</dt><dd className="mono">{publication.processingConfiguration.identity}</dd>
        <dt>Ingestion policy</dt><dd>{publication.policyVersion}</dd>
    </dl>;
}
