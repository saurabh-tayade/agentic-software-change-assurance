import { features, roadmapOnlyCapabilities, routeHref, type Feature } from './features';
import { DashboardPage } from './DashboardPage';
import { NavigationIcon } from './NavigationIcon';

export function Overview() {
    const capabilities = features.filter(feature => feature.id === 'intelligence' || feature.status === 'planned');
    return <DashboardPage className="overview-page" title="Overview" description="Explore available engineering intelligence capabilities and the platform roadmap."
            actions={<a className="primary action-link" href={routeHref('intelligence')}>Explore Repository Intelligence <span aria-hidden="true">↗</span></a>}>
        <div className="release-note"><span className="badge green">Current release</span><strong>v0.1 — Repository Intelligence Foundation</strong></div>
        <section className="dashboard-grid" aria-label="Platform capabilities">
            {capabilities.map(feature => <article className={`dashboard-tile capability-tile${feature.status === 'available' ? ' capability-available' : ''}`} key={feature.id}>
                <div className="tile-heading"><NavigationIcon destination={feature.id} /><span className="eyebrow">{feature.milestone}</span>
                    <span className={`badge ${feature.status === 'available' ? 'green' : 'amber'}`}>{feature.status === 'available' ? 'Available' : 'Planned'}</span></div>
                <h3>{'dashboardLabel' in feature ? feature.dashboardLabel : feature.label}</h3><p>{feature.description}</p>
                <a className="tile-link" href={routeHref(feature.id)}>{feature.status === 'available' ? 'Open Repository Intelligence' : `Preview ${feature.label}`} <span aria-hidden="true">→</span></a>
            </article>)}
            {roadmapOnlyCapabilities.map(feature => <article className="dashboard-tile capability-tile" key={feature.id}>
                <div className="tile-heading"><NavigationIcon destination={feature.id} /><span className="eyebrow">{feature.milestone}</span><span className="badge amber">Planned</span></div>
                <h3>{feature.label}</h3><p>{feature.description}</p><span className="tile-footnote">Roadmap only · not available in v0.1</span>
            </article>)}
        </section>
        <p className="trust-footer">Agentic Software Change Assurance Platform helps engineering teams understand software behavior through verifiable evidence. Full change assurance is a development direction: v0.1 provides repository questions and source inspection, not analysis or verification of proposed changes.</p>
    </DashboardPage>;
}
export function PlannedFeature({ feature }: { feature: Feature }) {
    return <DashboardPage key={feature.id} className="planned-page" compact title={feature.label} description={feature.description}>
        <p className="eyebrow">Platform roadmap · {feature.milestone}{'roadmapName' in feature ? ` — ${feature.roadmapName}` : ''}</p>
        <section className="dashboard-tile planned-status"><span className="badge amber">Planned — not yet available</span><h3>Development status</h3>
            <p>This capability is planned for milestone {feature.milestone}. It is not implemented in v0.1. No release date has been announced.</p>
            <p>Today you can ask questions about prepared repository versions and inspect their supporting sources.</p>
            <a className="primary action-link" href={routeHref('intelligence')}>Explore Repository Intelligence</a></section>
    </DashboardPage>;
}
