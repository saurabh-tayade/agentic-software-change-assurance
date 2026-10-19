import { useEffect, useRef, useState } from 'react';
import { Modal } from '../platform/Modal';
import { routeHref, type RouteId } from '../platform/features';
import { findTopic, helpTopics } from './topics';
interface Props { topic: string | null; route: RouteId; onClose: () => void; onRestoreFocus: () => void }
export function HelpCenter({ topic, route, onClose, onRestoreFocus }: Props) {
    const [search, setSearch] = useState('');
    const [category, setCategory] = useState('All topics');
    const article = useRef<HTMLElement>(null);
    const heading = useRef<HTMLHeadingElement>(null);
    const current = findTopic(topic);
    const previous = useRef(topic);
    useEffect(() => {
        if (topic && !previous.current) { setSearch(''); setCategory('All topics'); }
        if (topic && previous.current && topic !== previous.current) { heading.current?.focus(); article.current?.scrollTo?.(0, 0); }
        previous.current = topic;
    }, [topic]);
    const matches = helpTopics.filter(item => (category === 'All topics' || item.category === category)
        && [item.title, item.summary, ...item.paragraphs].join(' ').toLowerCase().includes(search.trim().toLowerCase()));
    return <Modal open={topic !== null} onOpenChange={open => { if (!open) onClose(); }} title="Help Center"
        description="Practical guidance for Change Assurance · v0.1" className="help-dialog" onRestoreFocus={onRestoreFocus}>
        <div className="help-layout"><aside className="help-navigation" aria-label="Help topics">
            <label htmlFor="help-search">Search help</label><input id="help-search" type="search" value={search} onChange={e => setSearch(e.target.value)} placeholder="Try Vector, citations, or coverage" />
            <label htmlFor="help-category">Category</label><select id="help-category" value={category} onChange={e => setCategory(e.target.value)}>
                {['All topics', ...new Set(helpTopics.map(item => item.category))].map(item => <option key={item}>{item}</option>)}
            </select><p className="small muted" role="status">{matches.length} {matches.length === 1 ? 'topic' : 'topics'}</p>
            <nav aria-label="Help topic navigation">{matches.map(item => <a key={item.id} href={routeHref(route, item.id)} aria-current={current.id === item.id ? 'page' : undefined}>{item.title}</a>)}</nav>
            {!matches.length && <p>No matching topics. Try a shorter search or another category.</p>}
        </aside><article className="help-article" ref={article}><span className="eyebrow">{current.category}</span><h3 ref={heading} tabIndex={-1}>{current.title}</h3>
            <p className="help-summary">{current.summary}</p>{current.paragraphs.map((text, i) => <p key={i}>{text}</p>)}
            <a className="text-button" href={routeHref(route, current.id)}>Link to this topic</a>
        </article></div>
    </Modal>;
}
