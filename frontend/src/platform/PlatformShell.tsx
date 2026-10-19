import { useEffect, useRef, useState, type ReactNode } from 'react';
import { features, routeHref, type Feature } from './features';
import { Modal } from './Modal';
import { NavigationIcon } from './NavigationIcon';
import { Hint } from './Hint';
export function PlatformShell({ feature, onHelp, helpOpen, children }: { feature: Feature; helpOpen: boolean; onHelp: () => void; children: ReactNode }) {
    const [menuOpen, setMenuOpen] = useState(false);
    const navigating = useRef(false);
    const heading = useRef<HTMLHeadingElement>(null);
    const previous = useRef(feature.id);
    useEffect(() => {
        if (previous.current !== feature.id) { navigating.current = true; setMenuOpen(false); heading.current?.focus(); previous.current = feature.id; }
    }, [feature.id]);
    useEffect(() => { if (helpOpen) setMenuOpen(false); }, [helpOpen]);
    const links = (drawer = false) => features.map(item => <a key={item.id} href={routeHref(item.id)}
        aria-current={item.id === feature.id ? 'page' : undefined} aria-label={`${item.label}${item.status === 'planned' ? ` — Planned ${item.milestone}` : ''}`}
        onClick={() => { navigating.current = item.id !== feature.id; setMenuOpen(false); }} className={`nav-link ${item.id === feature.id ? 'active' : ''}`}>
        <NavigationIcon destination={item.id} /><span>{drawer ? item.label : item.shortLabel}</span>
</a>);
    return <div className={`platform-shell${feature.id === 'intelligence' ? '' : ' dashboard-shell'}`}>
        <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus(); }}>Skip to main content</a>
        <header className="platform-header">
            <button className="icon-button menu-button" onClick={() => { navigating.current = false; setMenuOpen(true); }} aria-label="Open platform navigation" aria-expanded={menuOpen}>☰</button>
            <a href={routeHref('overview')} className="platform-brand"><span className="brand-mark" aria-hidden="true">⌘</span><span><strong>Change Assurance</strong><small>Evidence-backed Engineering</small></span></a>
            <Hint text="Help Center"><button id="global-help" className="help-button" aria-label="Open Help Center" onClick={onHelp}>?</button></Hint>
        </header>
        <nav className="platform-rail" aria-label="Platform">{links()}<span className="rail-release">v0.1</span></nav>
        <Modal open={menuOpen && !helpOpen} onOpenChange={setMenuOpen} title="Platform navigation" description="Choose a capability. Planned modules are previews only." className="navigation-dialog"
            onRestoreFocus={() => { if (helpOpen) return; if (navigating.current) heading.current?.focus(); else document.querySelector<HTMLButtonElement>('.menu-button')?.focus(); }}>
            <nav className="drawer-links" aria-label="Platform drawer">{links(true)}</nav>
        </Modal>
        <main id="main-content" className={`platform-main module-${feature.id}`} tabIndex={-1}>
            <h1 ref={heading} tabIndex={-1} className="sr-only">{feature.label}</h1>{children}
        </main>
    </div>;
}
