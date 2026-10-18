import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { answer, capabilities, details, evidence, full, jsonResponse, limited, scoped, version } from './test/fixtures';
import type { AnswerStatus } from './api/types';
import { validQuestion } from './hooks/useWorkspace';

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => {
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

async function prepareQuestion() {
    const user = userEvent.setup(); render(<App />);
    await user.click(await screen.findByRole('button', { name: /aaaaaaaaaa/ }));
    await user.type(screen.getByLabelText('Repository question'), answer.question);
    await waitFor(() => expect(screen.getByRole('button', { name: /Ask repository/ })).toBeEnabled());
    return user;
}

it('uses configured strategies and submits only the question and selected strategy', async () => {
    const user = await prepareQuestion();
    expect(screen.getByRole('radio', { name: 'Keyword' })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: 'Vector' }));
    await user.click(screen.getByRole('button', { name: /Ask repository/ }));
    await screen.findByRole('heading', { name: 'Answer & supporting evidence' });
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
    await user.click(screen.getByRole('button', { name: /Ask repository/ }));
    const response = await screen.findByRole('article');
    expect(within(response).getByText(status.replaceAll('_', ' ').toLowerCase())).toBeInTheDocument();
    expect(within(response).getByText('Deployment behavior is not established.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
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
    const input = screen.getByLabelText('Repository question');
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
    await user.click(await screen.findByRole('button', { name: /aaaaaaaaaa/ }));
    expect(screen.getByRole('radio', { name: /Vector/ })).toBeDisabled();
    const button = screen.getByRole('button', { name: /Ask repository/ });
    expect(button).toBeDisabled();
    await user.type(screen.getByLabelText('Repository question'), '123456789');
    expect(button).toBeDisabled(); expect(screen.getByText('9 / 8')).toBeInTheDocument();
});

it.each(['', '  \n', '\0hello', '\ud800', '\udfff', 'x'.repeat(513)])('rejects invalid question %j', question => {
    expect(validQuestion(question, 512)).toBe(false);
});
it('counts astral Unicode as two UTF-16 units', () => {
    expect(validQuestion('😀'.repeat(256), 512)).toBe(true);
    expect(validQuestion('😀'.repeat(257), 512)).toBe(false);
});

it('preserves the question and separates safe operational failure from insufficient evidence', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).endsWith('/answers')
        ? Promise.resolve(jsonResponse({ code: 'EMBEDDING_INDEX_INCOMPLETE', detail: 'secret SQL /private/path' }, 409)) : original(input, init));
    const user = await prepareQuestion();
    await user.click(screen.getByRole('button', { name: /Ask repository/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('complete embedding index');
    expect(screen.getByLabelText('Repository question')).toHaveValue(answer.question);
    expect(screen.queryByText(/secret SQL/)).not.toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

async function prepareAnswer() {
    const user = await prepareQuestion();
    await user.click(screen.getByRole('button', { name: /Ask repository/ }));
    await screen.findByRole('article');
    return user;
}

it('opens a claim citation by keyboard and renders original source safely with provenance', async () => {
    const user = await prepareAnswer();
    const claim = screen.getByRole('region', { name: 'Structured claims' });
    const citation = within(claim).getByRole('button', { name: /Open citation E5/ });
    citation.focus(); await user.keyboard('{Enter}');
    const source = await screen.findByRole('region', { name: 'Original source, lines 4 to 7' });
    expect(source).toHaveTextContent('<script>alert("untrusted")</script>');
    expect(source.querySelector('script')).toBeNull();
    expect(Array.from(source.querySelectorAll('.line-number'), item => item.textContent)).toEqual(['4', '5', '6', '7']);
    expect(Array.from(source.querySelectorAll('.source-line > span:last-child'), item => item.textContent).join('')).toBe(evidence.content);
    expect(screen.getByRole('heading', { name: 'Evidence explorer' })).toHaveFocus();
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/publications/${full.publicationId}/evidence/${evidence.evidence.chunkId}`, expect.anything());
    await user.click(screen.getByText('Source provenance'));
    expect(screen.getByText(evidence.evidence.contentHash)).toBeVisible();
    expect(within(screen.getByRole('complementary', { name: 'Evidence explorer' })).getByText(evidence.evidence.commitSha)).toBeVisible();
});

it('keeps the answer on evidence failure and allows a separate GET retry', async () => {
    const original = fetchMock.getMockImplementation()!;
    let attempts = 0;
    fetchMock.mockImplementation((input, init) => String(input).includes('/evidence/') && attempts++ === 0
        ? Promise.resolve(jsonResponse({ code: 'UNKNOWN_EVIDENCE', detail: 'private' }, 404)) : original(input, init));
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not available in the selected publication');
    expect(screen.getByRole('article')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry evidence' }));
    await screen.findByRole('region', { name: /Original source/ });
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

it('clears the answer and source when switching publications, while preserving the draft question', async () => {
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    await screen.findByRole('region', { name: /Original source/ });
    await user.click(screen.getByRole('button', { name: /bbbbbbbbbb/ }));
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Repository question')).toHaveValue(answer.question);
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
        render(<App />); await user.click(await screen.findByRole('button', { name: /aaaaaaaaaa/ }));
    } else {
        await user.click(screen.getByRole('button', { name: /Ask repository/ }));
        if (operation === 'evidence') await user.click(await screen.findByRole('button', { name: /Open citation E5/ }));
    }
    await user.click(screen.getByRole('button', { name: /cccccccccc/ }));
    await screen.findByText('Partial evidence coverage');
    expect(signal?.aborted).toBe(true);
    await act(async () => resolve(jsonResponse(operation === 'answer' ? answer : operation === 'evidence' ? evidence : details())));
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('Partial evidence coverage')).toBeInTheDocument();
});

it('rejects an evidence response with a different publication identity', async () => {
    const original = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation((input, init) => String(input).includes('/evidence/')
        ? Promise.resolve(jsonResponse({ ...evidence, evidence: { ...evidence.evidence, publicationId: scoped.publicationId } })) : original(input, init));
    const user = await prepareAnswer();
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_RESPONSE');
    expect(screen.queryByRole('region', { name: /Original source/ })).not.toBeInTheDocument();
    expect(screen.getByRole('article')).toBeInTheDocument();
});

it('submits HYBRID explicitly', async () => {
    const user = await prepareQuestion();
    await user.click(screen.getByRole('radio', { name: 'Hybrid' }));
    await user.click(screen.getByRole('button', { name: /Ask repository/ }));
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
    await user.click(screen.getByRole('button', { name: /Open citation E5/ }));
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
    await user.click(await screen.findByRole('button', { name: /aaaaaaaaaa/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('publication is unavailable');
    await user.type(screen.getByLabelText('Repository question'), answer.question);
    expect(screen.getByRole('button', { name: /Ask repository/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Retry details' }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Ask repository/ })).toBeEnabled());
});
afterEach(() => vi.unstubAllGlobals());

it('loads publications without selecting one automatically', async () => {
    render(<App />);
    expect(screen.getByText('Loading publications…')).toBeInTheDocument();
    await screen.findByRole('button', { name: /aaaaaaaaaa/ });
    expect(screen.getByText('Begin with a publication')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
});
it('distinguishes scopes and configurations and loads explicit details', async () => {
    const user = userEvent.setup(); render(<App />);
    const fullOption = await screen.findByRole('button', { name: /aaaaaaaaaa/ });
    expect(fullOption).toHaveTextContent('All paths');
    expect(screen.getByRole('button', { name: /bbbbbbbbbb/ })).toHaveTextContent('src');
    const limitedOption = screen.getByRole('button', { name: /cccccccccc/ });
    expect(limitedOption).toHaveTextContent('1 chunk limit');
    await user.click(limitedOption);
    await screen.findByText('Partial evidence coverage');
    expect(limitedOption).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByText('Provenance & coverage details'));
    expect(screen.getByText(limited.publicationId)).toBeVisible();
});
it('shows a useful empty catalog', async () => {
    fetchMock.mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : { apiVersion: version, items: [], nextAfter: null }));
    render(<App />);
    expect(await screen.findByText('Nothing indexed here yet')).toBeInTheDocument();
});
it('loads the next bounded page only when requested', async () => {
    fetchMock.mockImplementation(async input => jsonResponse(String(input).endsWith('/capabilities') ? capabilities
        : String(input).includes('&after=') ? { apiVersion: version, items: [scoped], nextAfter: null }
        : { apiVersion: version, items: [full], nextAfter: full.publicationId }));
    const user = userEvent.setup(); render(<App />);
    await user.click(await screen.findByRole('button', { name: 'Load more publications' }));
    await screen.findByRole('button', { name: /bbbbbbbbbb/ });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(`/api/v1/publications?limit=20&after=${full.publicationId}`, expect.anything()));
});
