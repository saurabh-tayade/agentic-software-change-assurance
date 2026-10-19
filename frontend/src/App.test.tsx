import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { answer, capabilities, details, evidence, full, jsonResponse, limited, scoped, version } from './test/fixtures';
import type { AnswerStatus } from './api/types';
import { validQuestion } from './hooks/useWorkspace';

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => {
    window.history.replaceState(null, "", "/#/intelligence");
    fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockImplementation(async input => {
        const path = String(input);
        if (path.endsWith('/capabilities')) return jsonResponse(capabilities);
        if (path.includes('?limit=20')) return jsonResponse({ apiVersion: version, items: [full, scoped, limited], nextAfter: null });
        if (path.endsWith('/answers')) return jsonResponse(answer);
        if (path.includes('/evidence/')) return jsonResponse(evidence);
        const publication = [full, scoped, limited].find(item => path.endsWith(item.publicationId));
        if (publication) return jsonResponse(details(publication));
        throw new Error('Unexpected test request');
    });
});

async function chooseVersion(user: ReturnType<typeof userEvent.setup>, id = full.publicationId) {
    if (screen.queryByRole('dialog', { name: 'Supporting Sources' })) await user.keyboard('{Escape}');
    await screen.findByRole('option', { name: full.repositoryId });
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    await user.selectOptions(screen.getByLabelText('Version'), id);
}

async function prepareQuestion() {
    const user = userEvent.setup(); render(<App />);
    await chooseVersion(user);
    await user.type(screen.getByLabelText('Ask'), answer.question);
    await waitFor(() => expect(screen.getByRole('button', { name: /Ask Question/ })).toBeEnabled());
    return user;
}

it('uses configured strategies and submits only the question and selected strategy', async () => {
    const user = await prepareQuestion();
    expect(screen.getByLabelText('Search Method')).toHaveValue('KEYWORD');
    await user.selectOptions(screen.getByLabelText('Search Method'), 'VECTOR');
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    await screen.findByRole('heading', { name: 'Answer' });
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.[0]).toBe(`/api/v1/publications/${full.publicationId}/answers`);
    expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({ question: answer.question, retrievalStrategy: 'VECTOR' });
});

it.each<AnswerStatus>(['SUPPORTED', 'PARTIALLY_SUPPORTED', 'INSUFFICIENT_EVIDENCE'])('presents %s as an answer result with uncertainty', async status => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/answers')
        ? Promise.resolve(jsonResponse({ ...answer, status, ...(status === 'INSUFFICIENT_EVIDENCE' ? { claims: [], citations: [] } : {}) })) : original(input, init));
    const user = await prepareQuestion();
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    const response = await screen.findByRole('article');
    expect(within(response).getByText(status.replaceAll('_', ' ').toLowerCase())).toBeInTheDocument();
    expect(within(response).queryByRole('button', { name: /Open citation/ })).not.toBeInTheDocument();
    expect(within(response).queryByText(/uncertainty statement/)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    if (status === 'INSUFFICIENT_EVIDENCE') expect(screen.getByText('No citations returned.')).toBeVisible();
    expect(screen.getByText('Deployment behavior is not established.')).toBeVisible();
    await user.click(screen.getByText('Retrieval & execution details'));
    expect(screen.getByText(answer.validation)).toBeVisible();
    expect(screen.getByText(/CONTEXT_BYTE_LIMIT/)).toBeVisible();
    expect(screen.getByText('Unavailable')).toBeVisible();
    expect(screen.getByText('0', { selector: 'dd' })).toBeVisible();
});

it('prevents duplicate inference while pending, including keyboard submission', async () => {
    let resolve!: (value: Response) => void;
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/answers')
        ? new Promise<Response>(done => { resolve = done; }) : original(input, init));
    const user = await prepareQuestion();
    const input = screen.getByLabelText('Ask');
    input.focus(); await user.keyboard('{Control>}{Enter}{/Control}');
    expect(screen.getByRole('button', { name: /Generating answer/ })).toBeDisabled();
    expect(input).toBeDisabled();
    fireEvent.submit(input.closest('form')!);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    await act(async () => resolve(jsonResponse(answer)));
});

it('uses the advertised length and disables unconfigured strategies', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/capabilities')
        ? Promise.resolve(jsonResponse({ ...capabilities, questionLengthLimit: 8, configuredRetrievalStrategies: ['KEYWORD'] })) : original(input, init));
    const user = userEvent.setup(); render(<App />);
    await chooseVersion(user);
    expect(screen.getByRole('option', { name: /Vector/ })).toBeDisabled();
    const button = screen.getByRole('button', { name: /Ask Question/ });
    expect(button).toBeDisabled();
    await user.type(screen.getByLabelText('Ask'), '123456789');
    expect(button).toBeDisabled(); expect(screen.getByText('9 / 8')).toBeInTheDocument();
});

it.each(['', '  \n', '\0hello', '\ud800', '\udfff', 'x'.repeat(513)])('rejects invalid question %j', question => {
    expect(validQuestion(question, 512)).toBe(false);
});
it('counts astral Unicode as two UTF-16 units', () => {
    expect(validQuestion('😀'.repeat(256), 512)).toBe(true);
    expect(validQuestion('😀'.repeat(257), 512)).toBe(false);
});

it('groups guidance, UTF-16 count, shortcut and submission while preserving validation and Meta+Enter', async () => {
    const user = await prepareQuestion();
    const input = screen.getByLabelText('Ask');
    const controls = input.closest('form')!.querySelector('.question-controls')! as HTMLElement;
    expect(within(controls).getByText('Ask about code or docs.')).toBeVisible();
    expect(within(controls).getByText('Ctrl / ⌘ + Enter')).toBeVisible();
    expect(input).toHaveAccessibleDescription(/Ask about code or docs/);
    fireEvent.change(input, { target: { value: '😀'.repeat(257) } });
    expect(within(controls).getByText('514 / 512')).toBeVisible();
    expect(within(controls).getByRole('button', { name: /Ask Question/ })).toBeDisabled();
    expect(input).toHaveAccessibleDescription(/at most 512 UTF-16 characters/);
    fireEvent.change(input, { target: { value: '😀'.repeat(256) } });
    expect(within(controls).getByText('512 / 512')).toBeVisible();
    expect(within(controls).getByRole('button', { name: /Ask Question/ })).toBeEnabled();
    input.focus(); await user.keyboard('{Meta>}{Enter}{/Meta}');
    await screen.findByRole('article');
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(JSON.parse(String(posts[0]?.[1]?.body)).question).toBe('😀'.repeat(256));
});

it('preserves the question and separates safe operational failure from insufficient evidence', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/answers')
        ? Promise.resolve(jsonResponse({ code: 'EMBEDDING_INDEX_INCOMPLETE', detail: 'secret SQL /private/path' }, 409)) : original(input, init));
    const user = await prepareQuestion();
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('complete embedding index');
    expect(screen.getByLabelText('Ask')).toHaveValue(answer.question);
    expect(screen.queryByText(/secret SQL/)).not.toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

async function prepareAnswer() {
    const user = await prepareQuestion();
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    await screen.findByRole('article');
    return user;
}

it('opens a claim citation by keyboard and renders original source safely with provenance', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    const claim = screen.getByRole('region', { name: 'Structured claims' });
    const citation = within(claim).getByRole('button', { name: /Open citation E5/ });
    citation.focus(); await user.keyboard('{Enter}');
    const source = await screen.findByRole('region', { name: 'Original source, lines 4 to 7' });
    expect(source).toHaveTextContent('<script>alert("untrusted")</script>');
    expect(source.querySelector('script')).toBeNull();
    expect(Array.from(source.querySelectorAll('.line-number'), item => item.textContent)).toEqual(['4', '5', '6', '7']);
    expect(Array.from(source.querySelectorAll('.source-line > span:last-child'), item => item.textContent).join('')).toBe(evidence.content);
    expect(screen.getByRole('heading', { name: 'Original source excerpt', level: 2 })).toHaveFocus();
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/publications/${full.publicationId}/evidence/${evidence.evidence.chunkId}`, expect.anything());
    await user.click(screen.getByText('Source Details'));
    expect(screen.getByText(evidence.evidence.contentHash)).toBeVisible();
    expect(within(screen.getByRole('complementary', { name: 'Original source excerpt' })).getByText(evidence.evidence.commitSha)).toBeVisible();
});

it('keeps the answer on evidence failure and allows a separate GET retry', async () => {
    const original = fetchMock.getMockImplementation()!;
    let attempts = 0;
    fetchMock.mockImplementation((input, init) => String(input).includes('/evidence/') && attempts++ === 0
        ? Promise.resolve(jsonResponse({ code: 'UNKNOWN_EVIDENCE', detail: 'private' }, 404)) : original(input, init));
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not available in the selected publication');
    expect(screen.getByRole('article', { hidden: true })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry evidence' }));
    await screen.findByRole('region', { name: /Original source/ });
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

it('clears the answer and source when switching publications, while preserving the draft question', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await screen.findByRole('region', { name: /Original source/ });
    await chooseVersion(user, scoped.publicationId);
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Ask')).toHaveValue(answer.question);
});

it('preserves the loaded answer and evidence when re-selecting the active publication', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    const source = await screen.findByRole('region', { name: /Original source/ });
    const response = screen.getByRole('article', { hidden: true });
    const requests = fetchMock.mock.calls.length;
    await chooseVersion(user, full.publicationId);
    expect(screen.getByRole('article')).toBe(response);
    await user.click(screen.getByRole('button', { name: 'Supporting Sources' }));
    expect(screen.getByRole('region', { name: /Original source/ }).textContent).toBe(source.textContent);
    expect(fetchMock).toHaveBeenCalledTimes(requests);
});

it.each([full, scoped, limited])('identifies publication $publicationId in the question composer', async publication => {
    const user = userEvent.setup(); render(<App />);
    await chooseVersion(user, publication.publicationId);
    const context = screen.getByRole('group', { name: 'Question publication' });
    expect(context).toHaveTextContent(publication.repositoryId);
    expect(context).toHaveTextContent(`Revision: ${publication.commitSha.slice(0, 10)}`);
    expect(context).toHaveTextContent(`Publication: ${publication.publicationId.slice(0, 10)}`);
    expect(context).not.toHaveTextContent('chunk limit');
    expect(context).not.toHaveTextContent('lines/chunk');
});

it('distinguishes loading and failed capabilities from strategies reported as unconfigured', async () => {
    const original = fetchMock.getMockImplementation()!;
    let resolve!: (value: Response) => void;
    let attempts = 0;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/capabilities')
        ? attempts++ === 0 ? new Promise<Response>(done => { resolve = done; })
            : Promise.resolve(jsonResponse({ ...capabilities, configuredRetrievalStrategies: ['KEYWORD'] }))
        : original(input, init));
    const user = userEvent.setup(); render(<App />);
    expect(screen.getByRole('option', { name: 'Loading search methods…' })).toBeVisible();
    expect(screen.getAllByRole('option', { name: /configuration unknown/ })).toHaveLength(3);
    expect(screen.queryByRole('option', { name: /not configured/ })).not.toBeInTheDocument();
    await act(async () => resolve(jsonResponse({ code: 'RETRIEVAL_FAILURE' }, 503)));
    expect(screen.getByRole('option', { name: 'Search methods unavailable' })).toBeVisible();
    expect(screen.getAllByRole('option', { name: /configuration unknown/ })).toHaveLength(3);
    expect(screen.queryByRole('option', { name: /not configured/ })).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Repository evidence could not be read');
    await user.click(screen.getByRole('button', { name: 'Retry capabilities' }));
    expect(await screen.findByRole('option', { name: 'Keyword' })).toBeEnabled();
    expect(screen.getByLabelText('Search Method')).toHaveValue('KEYWORD');
    expect(screen.getByRole('option', { name: /Vector\s*\(not configured\)/ })).toBeDisabled();
    expect(screen.getByRole('option', { name: /Hybrid\s*\(not configured\)/ })).toBeDisabled();
    expect(screen.queryByRole('option', { name: /configuration unknown/ })).not.toBeInTheDocument();
});

it.each(['answer', 'evidence', 'detail'])('ignores a stale %s response even if transport does not honor cancellation', async operation => {
    const original = fetchMock.getMockImplementation()!;
    let resolve!: (value: Response) => void;
    let signal: AbortSignal | null | undefined;
    fetchMock.mockImplementation((input, init) => {
        const path = String(input);
        if ((operation === 'answer' && path.endsWith('/answers')) || (operation === 'evidence' && path.includes('/evidence/'))
            || (operation === 'detail' && path.endsWith(full.publicationId))) {
            signal = init?.signal;
            return new Promise<Response>(done => { resolve = done; });
        }
        return original(input, init);
    });
    const user = operation === 'detail' ? userEvent.setup() : await prepareQuestion();
    if (operation === 'detail') {
        render(<App />); await chooseVersion(user);
    } else {
        await user.click(screen.getByRole('button', { name: /Ask Question/ }));
        if (operation === 'evidence') {
            await user.click(await screen.findByRole('button', { name: 'View full answer' }));
            await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
        }
    }
    await chooseVersion(user, limited.publicationId);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Details' })).toBeEnabled());
    expect(signal?.aborted).toBe(true);
    await act(async () => resolve(jsonResponse(operation === 'answer' ? answer : operation === 'evidence' ? evidence : details())));
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Question publication' })).toHaveTextContent('partial');
});

it('rejects an evidence response with a different publication identity', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).includes('/evidence/')
        ? Promise.resolve(jsonResponse({ ...evidence, evidence: { ...evidence.evidence, publicationId: scoped.publicationId } })) : original(input, init));
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_RESPONSE');
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('article')).toBeInTheDocument();
});

it('submits HYBRID explicitly', async () => {
    const user = await prepareQuestion();
    await user.selectOptions(screen.getByLabelText('Search Method'), 'HYBRID');
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    await screen.findByRole('article');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST').map(([, init]) => JSON.parse(String(init?.body)).retrievalStrategy)).toEqual(['HYBRID']);
});

it('keeps the most recently requested excerpt when an older evidence response arrives late', async () => {
    const original = fetchMock.getMockImplementation()!;
    let resolve!: (response: Response) => void;
    fetchMock.mockImplementation((input, init) => {
        const path = String(input);
        if (path.includes('/evidence/' + evidence.evidence.chunkId)) return new Promise<Response>(done => { resolve = done; });
        if (path.includes('/evidence/' + '1'.repeat(64))) return Promise.resolve(jsonResponse({ ...evidence,
            evidence: { ...evidence.evidence, chunkId: '1'.repeat(64), path: 'README.md', sourceFormat: 'MARKDOWN' },
            content: '# Source\n<script>alert(1)</script>\n[link](javascript:alert(1))\ntext\n' }));
        return original(input, init);
    });
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByText('Retrieval & execution details'));
    await user.click(screen.getAllByRole('button', { name: 'src/QuotePolicy.java:4–7' })[0]!);
    const source = await screen.findByRole('region', { name: /Original source/ });
    expect(source).toHaveTextContent('[link](javascript:alert(1))');
    expect(source.querySelector('a, script')).toBeNull();
    await act(async () => resolve(jsonResponse(evidence)));
    expect(screen.getByRole('region', { name: /Original source/ })).toHaveTextContent('# Source');
});

it('does not store answers or source in browser storage', async () => {
    const save = vi.spyOn(Storage.prototype, 'setItem');
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await screen.findByRole('region', { name: /Original source/ });
    expect(save).not.toHaveBeenCalled();
});

it('disables submission until selected publication details succeed and offers retry', async () => {
    const original = fetchMock.getMockImplementation()!;
    let attempts = 0;
    fetchMock.mockImplementation((input, init) => String(input).endsWith(full.publicationId) && attempts++ === 0
        ? Promise.resolve(jsonResponse({ code: 'UNKNOWN_PUBLICATION' }, 404)) : original(input, init));
    const user = userEvent.setup(); render(<App />);
    await chooseVersion(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('publication is unavailable');
    expect(screen.getByLabelText('Ask')).toBeDisabled();
    expect(screen.getByRole('button', { name: /Ask Question/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Retry details' }));
    await waitFor(() => expect(screen.getByLabelText('Ask')).toBeEnabled());
    await user.type(screen.getByLabelText('Ask'), answer.question);
    await waitFor(() => expect(screen.getByRole('button', { name: /Ask Question/ })).toBeEnabled());
});
afterEach(() => vi.unstubAllGlobals());

it('loads publications without selecting one automatically', async () => {
    render(<App />);
    expect(screen.getByText('Loading publications…')).toBeInTheDocument();
    await screen.findByRole('option', { name: full.repositoryId });
    expect(screen.getByText('Select the repository and immutable version to explore.')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('distinguishes same-commit versions and exposes full coverage and limitations in Details', async () => {
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole('option', { name: full.repositoryId });
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    expect(screen.getByRole('option', { name: /aaaaaaaaaa/ })).toHaveTextContent('All paths');
    expect(screen.getByRole('option', { name: /bbbbbbbbbb/ })).toHaveTextContent('src');
    expect(screen.getByRole('option', { name: /cccccccccc/ })).toHaveTextContent('Single excerpt');
    await user.selectOptions(screen.getByLabelText('Version'), limited.publicationId);
    const trigger = screen.getByRole('button', { name: 'Details' });
    await waitFor(() => expect(trigger).toBeEnabled()); await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Publication Details' });
    expect(within(dialog).getByText('Partial evidence coverage')).toBeVisible();
    expect(within(dialog).getAllByText(limited.publicationId)[0]).toBeVisible();
    expect(within(dialog).getAllByText(limited.commitSha)[0]).toBeVisible();
    expect(within(dialog).getByText(/Scope describes selected paths/)).toBeVisible();
    await user.click(within(dialog).getByText('Processing configuration & provenance'));
    expect(within(dialog).getByText('4 lines/chunk · 1 chunk limit')).toBeVisible();
    await user.click(within(dialog).getByText('Ingestion & processing limitations (2)'));
    expect(within(dialog).getByText('Committed source only.')).toBeVisible();
    expect(within(dialog).getByText('Chunk budget reached.')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus();
});
it('shows a useful empty catalog', async () => {
    fetchMock.mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : { apiVersion: version, items: [], nextAfter: null }));
    render(<App />);
    expect(await screen.findByText(/No repositories available. Prepare/)).toBeInTheDocument();
});
it('loads the next bounded page only when requested', async () => {
    fetchMock.mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : String(input).includes('&after=') ? { apiVersion: version, items: [scoped], nextAfter: null }
        : { apiVersion: version, items: [full], nextAfter: full.publicationId }));
    const user = userEvent.setup(); render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Load more publications' }));
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    await screen.findByRole('option', { name: /bbbbbbbbbb/ });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`/api/v1/publications?limit=20&after=${full.publicationId}`, expect.anything()));
});
it('keeps the selected search method visible and examples editable without submitting', async () => {
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole('option', { name: full.repositoryId });
    expect(screen.getByLabelText('Search Method')).toBeVisible();
    expect(screen.getByLabelText('Search Method')).toHaveValue('KEYWORD');
    await chooseVersion(user);
    await user.click(screen.getByText('Example questions'));
    await user.click(screen.getByRole('button', { name: 'How is shipping cost calculated?' }));
    const input = screen.getByLabelText('Ask');
    expect(input).toHaveValue('How is shipping cost calculated?');
    expect(input).toHaveFocus();
    await user.type(input, ' Explain the rule.');
    expect(input).toHaveValue('How is shipping cost calculated? Explain the rule.');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});

it('keeps the original answer available in a focus-managed full-answer dialog', async () => {
    const user = await prepareAnswer();
    const trigger = screen.getByRole('button', { name: 'View full answer' });
    expect(screen.getByLabelText('Ask')).toBeVisible();
    expect(screen.getByRole('article')).toHaveTextContent(answer.answer);
    await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Full Answer' });
    expect(within(dialog).getByText(answer.answer, { selector: '.answer-text' })).toBeVisible();
    expect(within(dialog).getByRole('region', { name: 'Structured claims' })).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    await user.click(screen.getByLabelText('Ask'));
    expect(screen.getByLabelText('Ask')).toHaveValue(answer.question);
    expect(screen.getByLabelText('Ask')).toHaveFocus();
});

it('preserves answers and strategy when navigating away and back without another POST', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('link', { name: 'Overview' }));
    await screen.findByText('v0.1 — Repository Intelligence Foundation');
    expect(screen.queryByRole('article', { name: 'Answer' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Repository Intelligence' }));
    expect(await screen.findByRole('article', { name: 'Answer' })).toHaveTextContent(answer.answer);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});
it('opens detailed Help from the full answer without leaving overlapping modals', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Full Answer' })).getByRole('button', { name: 'About Answer status' }));
    await user.click(screen.getByRole('button', { name: 'Read more in Help' }));
    expect(await screen.findByRole('dialog', { name: 'Help Center' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Full Answer' })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Open Help Center' })).toHaveFocus();
    expect(screen.getByRole('article', { name: 'Answer' })).toHaveTextContent(answer.answer);
});

it('keeps selection and source controls reachable by keyboard', async () => {
    const user = userEvent.setup(); render(<App />);
    await chooseVersion(user);
    const search = screen.getByLabelText('Search Method'); search.focus(); await user.keyboard('{Tab}');
    expect(screen.getByRole('button', { name: 'About search methods' })).toHaveFocus();
    await user.keyboard('{Tab}'); expect(screen.getByRole('button', { name: 'Supporting Sources' })).toHaveFocus();
    await user.keyboard('{Enter}'); expect(screen.getByRole('heading', { name: 'Original source excerpt', level: 2 })).toHaveFocus();
    await user.keyboard('{Escape}'); expect(screen.getByRole('button', { name: 'Supporting Sources' })).toHaveFocus();
});

it('requires an explicit version, groups repositories once, and filters their versions', async () => {
    const other = { ...full, publicationId: 'e'.repeat(64), repositoryId: 'other-project' };
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).includes('?limit=20')
        ? Promise.resolve(jsonResponse({ apiVersion: version, items: [full, other, scoped, limited], nextAfter: null })) : original(input, init));
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole('option', { name: 'other-project' });
    expect(within(screen.getByLabelText('Repository')).getAllByRole('option')).toHaveLength(3);
    expect(screen.getByLabelText('Repository')).toHaveValue('');
    expect(screen.getByLabelText('Version')).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    expect(screen.getByLabelText('Version')).toHaveValue('');
    expect(within(screen.getByLabelText('Version')).getAllByRole('option')).toHaveLength(4);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await user.selectOptions(screen.getByLabelText('Repository'), 'other-project');
    expect(within(screen.getByLabelText('Version')).getAllByRole('option')).toHaveLength(2);
    expect(screen.getByRole('option', { name: /eeeeeeeeee/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /aaaaaaaaaa/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Version')).toHaveValue('');
    expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('starts with sources closed, and reopening a drawer preserves evidence without another GET or POST', async () => {
    const user = await prepareAnswer();
    const toggle = screen.getByRole('button', { name: 'Supporting Sources' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('complementary', { name: 'Supporting Sources' })).not.toBeInTheDocument();
    await user.click(toggle); expect(screen.getByText('Evidence lives here')).toBeVisible();
    await user.keyboard('{Escape}'); expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await screen.findByRole('region', { name: /Original source/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const requests = fetchMock.mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Close Supporting Sources' }));
    expect(toggle).toHaveFocus(); expect(screen.getByRole('article')).toHaveTextContent(answer.answer);
    await user.click(toggle);
    expect(screen.getByRole('region', { name: /Original source/ })).toHaveTextContent('return 800;');
    expect(fetchMock).toHaveBeenCalledTimes(requests);
});

it('clears selection and cancels late inference when the repository changes without selecting a new version', async () => {
    const original = fetchMock.getMockImplementation()!;
    const other = { ...full, repositoryId: 'other-project', publicationId: 'e'.repeat(64) };
    let resolve!: (response: Response) => void;
    let signal: AbortSignal | null | undefined;
    fetchMock.mockImplementation((input, init) => {
        if (String(input).includes('?limit=20')) return Promise.resolve(jsonResponse({ apiVersion: version, items: [full, scoped, other], nextAfter: null }));
        if (String(input).endsWith('/answers')) { signal = init?.signal; return new Promise(done => { resolve = done; }); }
        return original(input, init);
    });
    const user = await prepareQuestion(); await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    await user.selectOptions(screen.getByLabelText('Repository'), 'other-project');
    expect(screen.getByLabelText('Version')).toHaveValue('');
    expect(signal?.aborted).toBe(true);
    await act(async () => resolve(jsonResponse(answer)));
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Question publication' })).not.toHaveTextContent(full.publicationId.slice(0, 10));
    expect(screen.getByLabelText('Ask')).toHaveValue(answer.question);
    expect(screen.getByRole('button', { name: /Ask Question/ })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    expect(screen.getByLabelText('Version')).toHaveValue('');
});

it('clears preserved source evidence when the version selection is removed', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ })); await screen.findByRole('region', { name: /Original source/ });
    await user.keyboard('{Escape}');
    await user.selectOptions(screen.getByLabelText('Version'), '');
    expect(screen.getByLabelText('Repository')).toHaveValue(full.repositoryId);
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Supporting Sources' }));
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.getByText('Evidence lives here')).toBeVisible();
});

it('uses the server-advertised default rather than imposing Keyword', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/capabilities')
        ? Promise.resolve(jsonResponse({ ...capabilities, defaultRetrievalStrategy: 'HYBRID' })) : original(input, init));
    const user = await prepareQuestion(); expect(screen.getByLabelText('Search Method')).toHaveValue('HYBRID');
    await user.click(screen.getByRole('button', { name: /Ask Question/ })); await screen.findByRole('article');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST').map(([, init]) => JSON.parse(String(init?.body)).retrievalStrategy)).toEqual(['HYBRID']);
});

it('replaces the full answer with a responsive source sheet and restores focus on Escape', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Full Answer' })).getByRole('button', { name: /Open citation E5/ }));
    const sheet = await screen.findByRole('dialog', { name: 'Supporting Sources' });
    expect(screen.queryByRole('dialog', { name: 'Full Answer' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    const source = await within(sheet).findByRole('region', { name: /Original source/ });
    expect(source).toHaveTextContent('return 800;');
    expect(within(sheet).getByRole('complementary').querySelector('#evidence-title')).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const toggle = screen.getByRole('button', { name: 'Supporting Sources' });
    expect(toggle).toHaveFocus(); expect(screen.getByRole('article')).toHaveTextContent(answer.answer);
    await user.click(toggle); expect(await screen.findByRole('region', { name: /Original source/ })).toHaveTextContent('return 800;');
});

it('keeps the same modal and evidence through resize without refetching', async () => {
    const user = await prepareAnswer(); await user.click(screen.getByRole('button', { name: 'View full answer' }));
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await screen.findByRole('region', { name: /Original source/ });
    const sheet = screen.getByRole('dialog', { name: 'Supporting Sources' });
    const source = screen.getByRole('region', { name: /Original source/ });
    const requests = fetchMock.mock.calls.length;
    fireEvent(window, new Event('resize'));
    expect(screen.getByRole('dialog', { name: 'Supporting Sources' })).toBe(sheet);
    expect(screen.getByRole('region', { name: /Original source/ })).toBe(source);
    expect(fetchMock).toHaveBeenCalledTimes(requests);
});

it('unifies context, Ask, and empty guidance inside Explore without a separate summary strip or answer card', async () => {
    const user = userEvent.setup(); render(<App />);
    const explore = screen.getByRole('region', { name: 'Explore' });
    expect(within(explore).getByText(/Choose a repository and version to ask a question/)).toBeVisible();
    expect(within(explore).getByLabelText('Ask')).toBeVisible();
    expect(within(explore).getByRole('button', { name: 'Supporting Sources' })).toHaveAttribute('aria-expanded', 'false');
    expect(explore.querySelector('.workspace-context-bar, .answer-panel')).toBeNull();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    await chooseVersion(user);
    expect(within(explore).getByRole('group', { name: 'Question publication' })).toHaveTextContent(full.repositoryId);
    expect(within(explore).getByRole('button', { name: 'Details' })).toBeVisible();
});

it('keeps Ask editable and the original preview bound to its submitted question without new inference', async () => {
    const user = await prepareAnswer();
    const explore = screen.getByRole('region', { name: 'Explore' });
    const input = within(explore).getByLabelText('Ask');
    expect(input).toHaveValue(answer.question); expect(input).toBeEnabled();
    const preview = within(explore).getByRole('article', { name: 'Answer' });
    expect(preview).not.toHaveClass('panel'); expect(preview).toHaveTextContent(answer.answer);
    expect(within(preview).queryByRole('button', { name: /Open citation E5/ })).not.toBeInTheDocument();
    expect(within(preview).queryByText(/uncertainty statement/)).not.toBeInTheDocument();
    expect(within(preview).queryByText(answer.uncertainties[0]!)).not.toBeInTheDocument();
    expect(within(preview).queryByText(/factual correctness is not independently verified/)).not.toBeInTheDocument();
    const requests = fetchMock.mock.calls.length;
    await user.type(input, ' Explain.');
    expect(within(preview).getByRole('heading', { name: 'Previous answer' })).toBeVisible();
    expect(within(preview).getByRole('status')).toHaveTextContent(`Answer to: ${answer.question}`);
    expect(within(preview).getByRole('status')).toHaveTextContent('Your draft has changed');
    expect(preview).toHaveTextContent(answer.answer);
    const more = within(preview).getByRole('button', { name: 'View full answer' });
    expect(more).toHaveTextContent('...more'); await user.click(more);
    const dialog = screen.getByRole('dialog', { name: 'Full Answer' });
    for (const text of answer.uncertainties) expect(within(dialog).getByText(text)).toBeVisible();
    await user.keyboard('{Escape}'); expect(more).toHaveFocus();
    expect(input).toHaveValue(answer.question + ' Explain.');
    expect(fetchMock).toHaveBeenCalledTimes(requests);
});

it('opens examples by keyboard, dismisses with Escape, and moves focus to Ask after selection without submitting', async () => {
    const user = userEvent.setup(); render(<App />); await chooseVersion(user);
    const trigger = screen.getByRole('button', { name: 'Example questions' });
    trigger.focus(); await user.keyboard('{Enter}');
    const popover = screen.getByRole('dialog', { name: 'Example questions' });
    expect(within(popover).getAllByRole('button')).toHaveLength(6);
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(trigger);
    await user.click(screen.getByRole('button', { name: 'Which tests cover remote-destination pricing?' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Ask')).toHaveFocus();
    expect(screen.getByLabelText('Ask')).toHaveValue('Which tests cover remote-destination pricing?');
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});

it('dismisses examples when opening another modal or leaving the workspace', async () => {
    const user = userEvent.setup(); render(<App />); await chooseVersion(user);
    await user.click(screen.getByRole('button', { name: 'Example questions' }));
    await user.click(screen.getByRole('button', { name: 'Supporting Sources' }));
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(screen.getByRole('dialog', { name: 'Supporting Sources' })).toBeVisible();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Example questions' }));
    await user.click(screen.getByRole('link', { name: 'Overview' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('keeps native version options distinguishable with long scope labels and complete details', async () => {
    const long = { ...full, scope: { includes: ['src/' + 'long-component-name/'.repeat(12)], excludes: [] } };
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).includes('?limit=20')
        ? Promise.resolve(jsonResponse({ apiVersion: version, items: [long, scoped], nextAfter: null })) : original(input, init));
    const user = userEvent.setup(); render(<App />); await chooseVersion(user);
    const select = screen.getByRole('combobox', { name: 'Version' });
    expect(select.tagName).toBe('SELECT');
    expect(select).toHaveAttribute('title', expect.stringContaining(full.publicationId.slice(0, 10)));
    expect(within(select).getByRole('option', { name: /aaaaaaaaaa/ })).toHaveTextContent('…');
    expect(within(select).getByRole('option', { name: /bbbbbbbbbb/ })).toHaveTextContent('src');
});

it('disables Ask until an explicit publication successfully loads, including the loading interval', async () => {
    const original = fetchMock.getMockImplementation()!;
    let resolve!: (response: Response) => void;
    fetchMock.mockImplementation((input, init) => String(input).endsWith(full.publicationId)
        ? new Promise(done => { resolve = done; }) : original(input, init));
    const user = userEvent.setup(); render(<App />);
    const input = screen.getByLabelText('Ask');
    expect(input).toBeDisabled();
    expect(screen.getByText('Choose a repository and version to ask a question.')).toBeVisible();
    await screen.findByRole('option', { name: full.repositoryId });
    await user.selectOptions(screen.getByLabelText('Repository'), full.repositoryId);
    expect(input).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Version'), full.publicationId);
    expect(input).toBeDisabled(); expect(screen.getByText('Loading the selected version…')).toBeVisible();
    await act(async () => resolve(jsonResponse(details())));
    expect(input).toBeEnabled(); expect(screen.getByRole('button', { name: /Ask Question/ })).toBeDisabled();
    await user.type(input, answer.question);
    expect(screen.getByRole('button', { name: /Ask Question/ })).toBeEnabled();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});

it('keeps the input node stable and replaces the old answer only on explicit new submission', async () => {
    const user = await prepareQuestion(); const input = screen.getByLabelText('Ask');
    await user.click(screen.getByRole('button', { name: /Ask Question/ })); await screen.findByRole('article');
    expect(screen.getByLabelText('Ask')).toBe(input); expect(input).toBeVisible();
    const original = fetchMock.getMockImplementation()!;
    let resolve!: (response: Response) => void;
    fetchMock.mockImplementation((url, init) => init?.method === 'POST' ? new Promise(done => { resolve = done; }) : original(url, init));
    await user.clear(input); await user.type(input, 'Where is input validation performed?');
    expect(screen.getByRole('article')).toHaveTextContent(answer.answer);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: /Ask Question/ }));
    expect(input).toBeDisabled(); expect(screen.queryByRole('article')).not.toBeInTheDocument();
    fireEvent.submit(input.closest('form')!);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(2);
    await act(async () => resolve(jsonResponse({ ...answer, question: 'Where is input validation performed?', answer: 'The selected source contains validation logic.' })));
    expect(screen.getByLabelText('Ask')).toBe(input); expect(input).toBeEnabled();
    expect(screen.getByRole('article')).toHaveTextContent('The selected source contains validation logic.');
    expect(screen.getByRole('article').querySelector('.answer-for')).toBeNull();
});

it('uses an accessible source tab and places Search Help beside its native select without Refresh', async () => {
    render(<App />); await screen.findByRole('option', { name: full.repositoryId });
    const tab = screen.getByRole('button', { name: 'Supporting Sources' });
    expect(tab).toHaveClass('sources-tab'); expect(tab).not.toHaveTextContent('+');
    expect(tab).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.queryByRole('button', { name: 'Refresh publications' })).not.toBeInTheDocument();
    const select = screen.getByLabelText('Search Method');
    expect(select.nextElementSibling).toBe(screen.getByRole('button', { name: 'About search methods' }));
});
