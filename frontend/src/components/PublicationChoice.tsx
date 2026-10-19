import type { PublicationReference } from '../api/types';
import { PublicationProvenance, scopeLabel, shortId, StatusBadge } from './Common';
export const repositoryName = (id: string): string => id.replace(/[-_]+/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
export function PublicationChoice({ publication, number, selected, onSelect }: {
    publication: PublicationReference; number: number; selected: boolean; onSelect: (publication: PublicationReference) => void;
}) {
    return <div className="version-choice"><button className={`publication-option ${selected ? 'selected' : ''}`} aria-pressed={selected}
        aria-label={`Open ${publication.repositoryId}, version ${number}, ${scopeLabel(publication)}, publication ${shortId(publication.publicationId)}`}
        onClick={() => onSelect(publication)}>
        <span className="publication-name">{repositoryName(publication.repositoryId)}</span>
        <span className="scope-label">Version {number} · {scopeLabel(publication)}</span>
        <span className="small muted">{publication.processingConfiguration.maxChunks === 1 ? 'Single-excerpt configuration' : 'Prepared source snapshot'}</span>
        <StatusBadge status={publication.processingStatus} />
    </button><details className="version-details"><summary>Version {number} details</summary>
        <p className="small">{publication.processingConfiguration.maxLines} lines/chunk · {publication.processingConfiguration.maxChunks.toLocaleString()} chunk limit</p>
        <PublicationProvenance publication={publication} />
    </details></div>;
}
