import type {
    AnswerRequest, AnswerResponse, CapabilitiesResponse, EvidenceResponse,
    PublicationDetailResponse, PublicationListResponse, Versioned,
} from './types';

export class ApiError extends Error {
    constructor(public readonly code: string, public readonly status: number) {
        super(errorMessage(code));
        this.name = 'ApiError';
    }
}

// Never render provider text, stack traces, or arbitrary server error descriptions.
export function errorMessage(code: string): string {
    switch (code) {
        case 'INVALID_REQUEST': return 'The request was not accepted. Check your question or publication selection.';
        case 'UNKNOWN_PUBLICATION': return 'This publication is unavailable. Select another publication or refresh the list.';
        case 'UNKNOWN_EVIDENCE': return 'This excerpt is not available in the selected publication.';
        case 'EMBEDDING_INDEX_INCOMPLETE': return 'This publication needs a complete embedding index. Try Keyword, or prepare the index with the CLI.';
        case 'EMBEDDING_PROFILE_MISMATCH': return 'The embedding profile does not match. Try Keyword, or check the local model configuration.';
        case 'MODEL_TIMEOUT': case 'EMBEDDING_MODEL_TIMEOUT': return 'The model timed out. Your question is preserved; submit again when you are ready.';
        case 'MODEL_UNAVAILABLE': case 'EMBEDDING_MODEL_UNAVAILABLE': return 'The local model is unavailable. Check Ollama and try again.';
        case 'RETRIEVAL_NOT_CONFIGURED': return 'This retrieval strategy is not configured on the API. Select an available strategy.';
        case 'RETRIEVAL_FAILURE': return 'Repository evidence could not be read. Check the local database and API.';
        case 'LOCAL_ACCESS_REQUIRED': case 'ORIGIN_NOT_ALLOWED': return 'Local access was rejected. Check the Vite proxy and API browser-origin configuration.';
        case 'CONTEXT_BUDGET_FAILURE': case 'EMBEDDING_INPUT_REJECTED': return 'The evidence could not fit the server’s input limits. Try a more focused question.';
        case 'INVALID_CITATION': case 'MALFORMED_MODEL_OUTPUT': case 'EMBEDDING_INVALID_VECTOR': case 'EMBEDDING_INVALID_RESPONSE':
            return 'The model response failed validation. No answer has been accepted.';
        case 'PUBLICATION_INTEGRITY_FAILURE': case 'EMBEDDING_INDEX_INTEGRITY_FAILURE':
            return 'Evidence integrity checks failed. Unverified source content will not be shown.';
        case 'NETWORK_ERROR': return 'Cannot reach the local API. Check that Spring Boot and the Vite proxy are running.';
        case 'INVALID_RESPONSE': return 'The API returned an unexpected response. Refresh or check that the API version matches this workspace.';
        default: return 'The operation could not be completed. Check the local API and try again.';
    }
}

export const isCancellation = (error: unknown): boolean => !!error && typeof error === 'object'
    && 'name' in error && error.name === 'AbortError';
export const asApiError = (error: unknown): ApiError => error instanceof ApiError ? error : new ApiError('NETWORK_ERROR', 0);

async function request<T extends Versioned>(path: string, signal: AbortSignal, body?: AnswerRequest): Promise<T> {
    let response: Response;
    try {
        response = await fetch(`/api/v1${path}`, {
            method: body ? 'POST' : 'GET', signal, cache: 'no-store', credentials: 'omit',
            headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
            ...(body ? { body: JSON.stringify(body) } : {}),
        });
    } catch (error) {
        if (isCancellation(error)) throw error;
        throw new ApiError('NETWORK_ERROR', 0);
    }
    let data: unknown;
    try { data = await response.json(); }
    catch (error) {
        if (isCancellation(error)) throw error;
        throw new ApiError(response.ok ? 'INVALID_RESPONSE' : 'HTTP_ERROR', response.status);
    }
    if (!response.ok) {
        const code = data && typeof data === 'object' && 'code' in data && typeof data.code === 'string'
            && /^[A-Z][A-Z_]{0,63}$/.test(data.code) ? data.code : 'HTTP_ERROR';
        throw new ApiError(code, response.status);
    }
    if (!data || typeof data !== 'object' || !('apiVersion' in data) || data.apiVersion !== 'repository-intelligence-v1') {
        throw new ApiError('INVALID_RESPONSE', response.status);
    }
    return data as T;
}

function id(value: string): string {
    if (!/^[0-9a-f]{64}$/.test(value)) throw new ApiError('INVALID_REQUEST', 400);
    return value;
}

export const api = {
    capabilities: (signal: AbortSignal) => request<CapabilitiesResponse>('/capabilities', signal),
    publications: (signal: AbortSignal, after: string | null = null) => request<PublicationListResponse>(
        `/publications?limit=20${after ? `&after=${id(after)}` : ''}`, signal),
    publication: (publicationId: string, signal: AbortSignal) => request<PublicationDetailResponse>(`/publications/${id(publicationId)}`, signal),
    answer: (publicationId: string, body: AnswerRequest, signal: AbortSignal) => request<AnswerResponse>(
        `/publications/${id(publicationId)}/answers`, signal, body),
    evidence: (publicationId: string, chunkId: string, signal: AbortSignal) => request<EvidenceResponse>(
        `/publications/${id(publicationId)}/evidence/${id(chunkId)}`, signal),
};
