import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { App } from '../App';
import { capabilities, full, scoped, limited, details, jsonResponse, version } from '../test/fixtures';
beforeEach(() => {
    window.history.replaceState(null, '', '/#/overview');
    vi.stubGlobal('fetch', vi.fn(async (input: string) => jsonResponse(input.endsWith('/capabilities') ? capabilities
        : input.includes('?limit=20') ? { apiVersion: version, items: [full, scoped, limited], nextAfter: null }
        : details([full, scoped, limited].find(p => input.endsWith(p.publicationId))))));
});
afterEach(() => vi.unstubAllGlobals());
it('introduces only the implemented capability and navigates to planned previews', async () => {
    const user = userEvent.setup(); render(<App />);
    expect(screen.getByText('v0.1 — Repository Intelligence Foundation')).toBeVisible();
    await user.click(screen.getByRole('link', { name: /Change Footprint — Planned M3/ }));
    expect(await screen.findByText('Planned — not yet available')).toBeVisible();
    expect(screen.getByText(/not implemented in v0.1/)).toBeVisible();
    await user.click(screen.getByRole('link', { name: 'Explore Repository Intelligence' }));
    expect(await screen.findByRole('heading', { name: 'Explore' })).toBeVisible();
});
it('supports direct routes and browser hash navigation', async () => {
    window.history.replaceState(null, '', '/#/contracts'); render(<App />);
    expect(screen.getByText(/planned for milestone M2/)).toBeVisible();
    window.history.replaceState(null, '', '/#/verification'); fireEvent(window, new HashChangeEvent('hashchange'));
    expect(await screen.findByText(/planned for milestone M4/)).toBeVisible();
});
it('groups repository versions and keeps the connection preview entirely disabled', async () => {
    window.history.replaceState(null, '', '/#/repositories'); const user = userEvent.setup(); render(<App />);
    expect(await screen.findByRole('region', { name: 'Repository parcel-quotes-v1' })).toBeVisible();
    expect(screen.getByText(/Available publications: 3/)).toBeVisible();
    expect(screen.getByText(/Source origin not recorded/)).not.toBeVisible();
    expect(screen.queryByRole('button', { name: 'Load Repository' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Connect Repository/ }));
    expect(screen.getByRole('button', { name: 'Load Repository' })).toBeDisabled();
    expect(screen.getByLabelText('Repository URL')).toBeDisabled();
    expect(screen.getByLabelText('Local repository/folder')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Load Repository' }));
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Close Connect a Repository' }));
    await user.click(screen.getByText('Inspect versions (3)'));
    expect(screen.getByText(/Source origin not recorded/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: /Open parcel-quotes-v1, version 2/ }));
    expect(await screen.findByRole('group', { name: 'Question publication' })).toHaveTextContent('bbbbbbbbbb');
});

it('searches and navigates Help, dismisses by keyboard, and restores focus', async () => {
    const user = userEvent.setup(); render(<App />);
    const trigger = screen.getByRole('button', { name: 'Open Help Center' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Help Center' });
    await user.type(within(dialog).getByLabelText('Search help'), 'Hybrid');
    await user.click(within(dialog).getByRole('link', { name: 'Search Strategies' }));
    expect(await within(dialog).findByRole('heading', { name: 'Search Strategies' })).toHaveFocus();
    expect(within(dialog).getByText(/Hybrid: Combines keyword/)).toBeVisible();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
});
it('supports direct topic URLs, category navigation, and empty search results', async () => {
    window.history.replaceState(null, '', '/#/repositories?help=versions');
    const user = userEvent.setup(); render(<App />);
    const dialog = screen.getByRole('dialog', { name: 'Help Center' });
    expect(within(dialog).getByRole('heading', { name: 'Repository Versions and Publications' })).toBeVisible();
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'Reference');
    expect(within(dialog).getByRole('link', { name: 'Glossary' })).toBeVisible();
    expect(within(dialog).queryByRole('link', { name: 'Getting Started' })).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Search help'), 'not-a-topic');
    expect(within(dialog).getByText(/No matching topics/)).toBeVisible();
});
it('provides a keyboard-accessible navigation drawer with a real planned destination', async () => {
    const user = userEvent.setup(); render(<App />);
    const menu = screen.getByRole('button', { name: 'Open platform navigation' });
    await user.click(menu);
    expect(screen.getByRole('dialog', { name: 'Platform navigation' })).toBeVisible();
    await user.keyboard('{Escape}'); expect(menu).toHaveFocus();
    await user.click(menu);
    await user.click(within(screen.getByRole('dialog')).getByRole('link', { name: /Assisted Verification/ }));
    expect(await screen.findByText(/planned for milestone M4/)).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('links touch-accessible contextual explanations to the same detailed guidance', async () => {
    window.history.replaceState(null, '', '/#/intelligence'); const user = userEvent.setup(); render(<App />);
    await screen.findByRole('option', { name: full.repositoryId });
    await user.click(screen.getByRole('button', { name: 'About search methods' }));
    const help = await screen.findByRole('dialog', { name: 'Help Center' });
    expect(within(help).getByRole('heading', { name: 'Search Strategies' })).toBeVisible();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.getByRole('button', { name: 'About search methods' })).toHaveFocus());
});

it('keeps repository groups separate and labels counts as loaded while pagination remains', async () => {
    const other = { ...full, publicationId: 'e'.repeat(64), repositoryId: 'another-project' };
    vi.mocked(fetch).mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : { apiVersion: version, items: [full, other, scoped], nextAfter: scoped.publicationId }));
    window.history.replaceState(null, '', '/#/repositories'); render(<App />);
    const parcel = await screen.findByRole('region', { name: 'Repository parcel-quotes-v1' });
    expect(within(parcel).getByText(/Loaded publications: 2/)).toBeVisible();
    const second = screen.getByRole('region', { name: 'Repository another-project' });
    expect(within(second).getByText(/Loaded publications: 1/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Load more publications' })).toBeEnabled();
});

it('dismisses search Help when browser history changes modules', async () => {
    window.history.replaceState(null, '', '/#/intelligence'); const user = userEvent.setup(); render(<App />);
    await screen.findByRole('option', { name: full.repositoryId });
    await user.click(screen.getByRole('button', { name: 'About search methods' }));
    expect(await screen.findByRole('dialog', { name: 'Help Center' })).toBeVisible();
    window.history.replaceState(null, '', '/#/overview'); fireEvent(window, new HashChangeEvent('hashchange'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByText('v0.1 — Repository Intelligence Foundation')).toBeVisible();
});

it('replaces an open navigation drawer when history opens a Help topic', async () => {
    const user = userEvent.setup(); render(<App />);
    await user.click(screen.getByRole('button', { name: 'Open platform navigation' }));
    window.history.replaceState(null, '', '/#/overview?help=roadmap'); fireEvent(window, new HashChangeEvent('hashchange'));
    expect(await screen.findByRole('dialog', { name: 'Help Center' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Platform navigation' })).not.toBeInTheDocument();
});

it('uses consistent decorative icons and keeps planned status on Change Footprint rather than repeated sidebar text', async () => {
    const user = userEvent.setup(); render(<App />);
    const nav = screen.getByRole('navigation', { name: 'Platform' });
    expect(nav.querySelectorAll('svg[aria-hidden="true"]')).toHaveLength(6);
    expect(nav).not.toHaveTextContent(/Planned|01|02|03/);
    const footprint = within(nav).getByRole('link', { name: 'Change Footprint — Planned M3' });
    expect(footprint).toHaveTextContent('Change Footprint'); await user.click(footprint);
    expect(screen.getByText('Planned — not yet available')).toBeVisible();
    expect(screen.getByText(/M3 — Change-Impact Analysis/)).toBeVisible();
    expect(screen.getByText(/positive, negative, or neutral/)).toBeVisible();
    expect(footprint).toHaveAttribute('aria-current', 'page');
});

it('makes the expanded Repository Intelligence guidance searchable with stakeholder examples and limits', async () => {
    const user = userEvent.setup(); render(<App />);
    await user.click(screen.getByRole('button', { name: 'Open Help Center' }));
    const help = screen.getByRole('dialog', { name: 'Help Center' });
    await user.type(within(help).getByLabelText('Search help'), 'system engineer');
    await user.click(within(help).getByRole('link', { name: 'Repository Intelligence' }));
    expect(within(help).getByText(/How does input validation connect to pricing/)).toBeVisible();
    expect(within(help).getByText(/What capabilities does this repository provide/)).toHaveTextContent('QA engineer');
    expect(within(help).getByText(/How repositories become available/)).toHaveTextContent('command-line');
    expect(within(help).getByText(/Reading statuses/)).toHaveTextContent('INSUFFICIENT_EVIDENCE');
    expect(within(help).getByText(/Why answers can be incomplete/)).toHaveTextContent('Missing evidence does not necessarily prove');
    expect(within(help).getByText(/Checking trust/)).toHaveTextContent('do not establish that the answer correctly interprets');
    expect(within(help).getByText(/Available and planned/)).toHaveTextContent('not available');
});

it('presents five honest roadmap tiles with real actions only for existing destinations', async () => {
    const user = userEvent.setup(); render(<App />);
    const grid = screen.getByRole('region', { name: 'Platform capabilities' });
    expect(within(grid).getAllByRole('article')).toHaveLength(5);
    expect(within(grid).getAllByText('Available')).toHaveLength(1);
    expect(within(grid).getAllByText('Planned')).toHaveLength(4);
    for (const milestone of ['M1', 'M2', 'M3', 'M4', 'M5']) expect(within(grid).getByText(milestone)).toBeVisible();
    const production = within(grid).getByRole('heading', { name: 'Production AI Engineering' }).closest('article')!;
    expect(within(production).queryByRole('link')).not.toBeInTheDocument();
    expect(production).toHaveTextContent('not available in v0.1');
    await user.click(within(grid).getByRole('link', { name: /Open Repository Intelligence/ }));
    expect(await screen.findByRole('heading', { name: 'Explore' })).toBeVisible();
    expect(screen.getByLabelText('Version')).toHaveValue('');
});

it.each([
    ['Contract & Dependency Intelligence', 'M2'], ['Change Footprint', 'M3'], ['Assisted Verification', 'M4'],
])('navigates from the %s tile to its existing planned page', async (name, milestone) => {
    const user = userEvent.setup(); render(<App />);
    await user.click(screen.getByRole('link', { name: `Preview ${name}` }));
    expect(await screen.findByText('Planned — not yet available')).toBeVisible();
    expect(screen.getByRole('heading', { level: 2, name })).toBeVisible();
    expect(screen.getByText(new RegExp(`planned for milestone ${milestone}`))).toBeVisible();
});

it('opens Explore for a repository without implicitly choosing any version, including a previously selected one', async () => {
    window.history.replaceState(null, '', '/#/repositories'); const user = userEvent.setup(); render(<App />);
    const tile = await screen.findByRole('region', { name: `Repository ${full.repositoryId}` });
    const inspect = within(tile).getByRole('button', { name: 'Inspect versions (3)' });
    inspect.focus(); await user.keyboard('{Enter}');
    expect(inspect).toHaveAttribute('aria-expanded', 'true');
    await user.click(within(tile).getByText('Version 2 details'));
    expect(within(tile).getByText(scoped.publicationId)).toBeVisible();
    expect(within(within(tile).getByText('Version 2 details').closest('details')!).getByText(scoped.commitSha)).toBeVisible();
    await user.click(within(tile).getByRole('button', { name: /Open parcel-quotes-v1, version 2/ }));
    await waitFor(() => expect(screen.getByLabelText('Ask')).toBeEnabled());
    expect(screen.getByLabelText('Version')).toHaveValue(scoped.publicationId);
    await user.click(screen.getByRole('link', { name: 'Repositories' }));
    await user.click(screen.getByRole('button', { name: `Explore ${full.repositoryId}` }));
    expect(screen.getByLabelText('Repository')).toHaveValue(full.repositoryId);
    expect(screen.getByLabelText('Version')).toHaveValue('');
    expect(screen.getByLabelText('Ask')).toBeDisabled();
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method !== 'POST')).toBe(true);
});

it('paginates and refreshes repository tiles without inferring a latest publication', async () => {
    window.history.replaceState(null, '', '/#/repositories');
    const longId = 'a-very-long-repository-identity-with-many-components-and-no-invented-origin';
    const other = { ...full, publicationId: 'e'.repeat(64), repositoryId: longId };
    vi.mocked(fetch).mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : String(input).includes('&after=') ? { apiVersion: version, items: [scoped, other], nextAfter: null }
        : { apiVersion: version, items: [full], nextAfter: full.publicationId }));
    const user = userEvent.setup(); render(<App />);
    const first = await screen.findByRole('region', { name: `Repository ${full.repositoryId}` });
    expect(first).toHaveTextContent('Loaded publications: 1');
    await user.click(screen.getByRole('button', { name: 'Load more publications' }));
    expect(await screen.findByRole('region', { name: `Repository ${longId}` })).toBeVisible();
    expect(first).toHaveTextContent('Available publications: 2');
    expect(screen.queryByRole('button', { name: 'Load more publications' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh Catalog' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: `Repository ${longId}` })).not.toBeInTheDocument());
    expect(first).toHaveTextContent('Loaded publications: 1');
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/publications?limit=20'))).toHaveLength(2);
});

it('keeps initial loading, safe errors, retry and empty-catalog recovery visible on Repositories', async () => {
    window.history.replaceState(null, '', '/#/repositories');
    let resolve!: (response: Response) => void;
    let calls = 0;
    vi.mocked(fetch).mockImplementation(async input => String(input).endsWith('/capabilities') ? jsonResponse(capabilities)
        : ++calls === 1 ? new Promise<Response>(done => { resolve = done; }) : jsonResponse({ apiVersion: version, items: [], nextAfter: null }));
    const user = userEvent.setup(); render(<App />);
    expect(screen.getByText('Loading repositories…', { selector: 'p' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Refresh Catalog' })).toBeDisabled();
    resolve(jsonResponse({ code: 'RETRIEVAL_UNAVAILABLE', detail: 'private database message' }, 503));
    expect(await screen.findByRole('alert')).toBeVisible();
    expect(screen.queryByText(/private database message/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry discovery' }));
    expect(await screen.findByRole('heading', { name: 'No repositories available' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Refresh Catalog' })).toBeEnabled();
});


it('opens the planned connection dialog by keyboard, traps focus, and restores its launcher', async () => {
    window.history.replaceState(null, '', '/#/repositories'); const user = userEvent.setup(); render(<App />);
    const launcher = screen.getByRole('button', { name: /Connect Repository/ });
    launcher.focus(); await user.keyboard('{Enter}');
    const dialog = screen.getByRole('dialog', { name: 'Connect a Repository' });
    expect(within(dialog).getByText('Planned — Not available in v0.1')).toBeVisible();
    expect(within(dialog).getByText(/existing ingestion CLI/)).toBeVisible();
    const close = within(dialog).getByRole('button', { name: 'Close Connect a Repository' });
    expect(close).toHaveFocus();
    await user.tab(); expect(close).toHaveFocus();
    await user.tab({ shift: true }); expect(close).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(launcher).toHaveFocus();
    await user.click(launcher); await user.click(screen.getByRole('button', { name: 'Close Connect a Repository' }));
    expect(launcher).toHaveFocus();
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
});

it('replaces the connection preview with Help on history navigation without overlapping dialogs', async () => {
    window.history.replaceState(null, '', '/#/repositories'); const user = userEvent.setup(); render(<App />);
    await user.click(screen.getByRole('button', { name: /Connect Repository/ }));
    window.history.replaceState(null, '', '/#/repositories?help=roadmap'); fireEvent(window, new HashChangeEvent('hashchange'));
    expect(await screen.findByRole('dialog', { name: 'Help Center' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Connect a Repository' })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Open Help Center' })).toHaveFocus();
});

it('keeps branding and Help in the global header and page context in content and navigation', async () => {
    const user = userEvent.setup(); const { container } = render(<App />);
    const header = container.querySelector('.platform-header')!;
    expect(header).toHaveTextContent('Change Assurance'); expect(header).toHaveTextContent('Evidence-backed Engineering');
    expect(header).not.toHaveTextContent('Overview'); expect(header).not.toHaveTextContent('Local · read-only');
    expect(screen.getByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    expect(container.querySelector('.dashboard-header')).toHaveTextContent('Explore Repository Intelligence');
    await user.click(screen.getByRole('link', { name: 'Repositories' }));
    expect(container.querySelector('.dashboard-header')).toHaveTextContent('Refresh Catalog');
    expect(container.querySelector('.dashboard-header')).toHaveTextContent('Connect Repository');
    await user.click(screen.getByRole('button', { name: 'Open Help Center' }));
    expect(await screen.findByRole('dialog', { name: 'Help Center' })).toBeVisible();
});

it('uses the same dashboard body on each dashboard route, remounts on navigation, and excludes Explore', async () => {
    const user = userEvent.setup(); const { container } = render(<App />);
    const overview = screen.getByRole('region', { name: 'Overview content' });
    overview.scrollTop = 120;
    await user.click(screen.getByRole('link', { name: 'Repositories' }));
    expect(screen.getByRole('region', { name: 'Repositories content' })).toHaveProperty('scrollTop', 0);
    await user.click(screen.getByRole('link', { name: 'Overview' }));
    expect(screen.getByRole('region', { name: 'Overview content' })).not.toBe(overview);
    expect(screen.getByRole('region', { name: 'Overview content' })).toHaveProperty('scrollTop', 0);
    await user.click(screen.getByRole('link', { name: /Contract & Dependency Intelligence — Planned/ }));
    const contracts = screen.getByRole('region', { name: 'Contract & Dependency Intelligence content' });
    contracts.scrollTop = 100;
    await user.click(screen.getByRole('link', { name: /Change Footprint — Planned/ }));
    const impact = screen.getByRole('region', { name: 'Change Footprint content' });
    expect(impact).not.toBe(contracts); expect(impact).toHaveProperty('scrollTop', 0);
    await user.click(screen.getByRole('link', { name: 'Explore Repository Intelligence' }));
    expect(container.querySelector('.dashboard-body')).toBeNull();
    expect(container.querySelector('.platform-shell')).not.toHaveClass('dashboard-shell');
    expect(screen.getByRole('heading', { name: 'Explore' })).toBeVisible();
});
