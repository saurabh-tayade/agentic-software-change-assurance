export const features = [
    { id: 'overview', label: 'Overview', shortLabel: 'Overview', status: 'available', milestone: 'v0.1', description: 'The platform, its purpose, and what is available today.' },
    { id: 'repositories', label: 'Repositories', shortLabel: 'Repos', status: 'available', milestone: 'v0.1', description: 'Explore the repository versions already prepared for analysis.' },
    { id: 'intelligence', label: 'Repository Intelligence', shortLabel: 'Ask', status: 'available', milestone: 'M1', description: 'Ask questions about committed code and inspect the sources behind an answer.' },
    { id: 'contracts', label: 'Contract & Dependency Intelligence', dashboardLabel: 'Contract Intelligence', shortLabel: 'Contracts', status: 'planned', milestone: 'M2', description: 'Make software contracts and dependencies easier to understand using engineering evidence.' },
    { id: 'impact', label: 'Change Footprint', shortLabel: 'Change Footprint', status: 'planned', milestone: 'M3', roadmapName: 'Change-Impact Analysis', description: 'Identify components, dependencies, interfaces, and potential effects of proposed software changes. These effects may be positive, negative, or neutral.' },
    { id: 'verification', label: 'Assisted Verification', shortLabel: 'Verify', status: 'planned', milestone: 'M4', description: 'Help engineers plan and assess verification activities using evidence about a change.' },
] as const;
export type Feature = typeof features[number];
export type RouteId = Feature['id'];
export const featureFor = (id: string): Feature => features.find(feature => feature.id === id) ?? features[0];
export const routeHref = (id: RouteId, help?: string): string => `#/${id}${help ? `?help=${encodeURIComponent(help)}` : ''}`;

// Overview also presents the documented M5 roadmap item without inventing a route.
export const roadmapOnlyCapabilities = [{
    id: 'production-ai', label: 'Production AI Engineering', milestone: 'M5', status: 'planned',
    description: 'Make AI quality, operational behavior, security, and cost measurable.',
}] as const;
