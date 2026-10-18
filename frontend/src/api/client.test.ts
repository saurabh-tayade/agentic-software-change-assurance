import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, isCancellation } from './client';

const publication = 'a'.repeat(64);
const signal = () => new AbortController().signal;
afterEach(() => vi.unstubAllGlobals());

describe('API boundary', () => {
    it('uses relative URLs, explicit identities and a single non-retried POST', async () => {
        const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ apiVersion: 'repository-intelligence-v1' })));
        vi.stubGlobal('fetch', fetch);
        await api.answer(publication, { question: 'Where?', retrievalStrategy: 'VECTOR' }, signal());
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(fetch).toHaveBeenCalledWith(`/api/v1/publications/${publication}/answers`, expect.objectContaining({
            method: 'POST', cache: 'no-store', body: '{"question":"Where?","retrievalStrategy":"VECTOR"}',
        }));
    });
    it('preserves safe codes without exposing server descriptions', async () => {
        const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'MODEL_UNAVAILABLE', detail: 'SECRET provider body' }), { status: 503 }));
        vi.stubGlobal('fetch', fetch);
        await expect(api.capabilities(signal())).rejects.toMatchObject({ code: 'MODEL_UNAVAILABLE', status: 503 });
        expect(new ApiError('MODEL_UNAVAILABLE', 503).message).not.toContain('SECRET');
        expect(fetch).toHaveBeenCalledTimes(1);
    });
    it('handles non-JSON proxy failures safely', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>stack trace</html>', { status: 500 })));
        await expect(api.capabilities(signal())).rejects.toMatchObject({ code: 'HTTP_ERROR' });
    });
    it('does not disguise cancellation as an operational failure', async () => {
        const abort = new DOMException('aborted', 'AbortError');
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort));
        await expect(api.capabilities(signal())).rejects.toBe(abort);
        expect(isCancellation(abort)).toBe(true);
    });
    it('rejects unrecognized response versions', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
        await expect(api.capabilities(signal())).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    });
    it('never constructs a path from an invalid evidence identity', () => {
        const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
        expect(() => api.evidence(publication, '../etc/passwd', signal())).toThrow(ApiError);
        expect(fetch).not.toHaveBeenCalled();
    });
});
