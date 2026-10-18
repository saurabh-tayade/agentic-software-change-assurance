import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, asApiError, isCancellation } from '../api/client';
import type { AnswerResponse, EvidenceReference, EvidenceResponse, PublicationDetailResponse, PublicationReference, Strategy } from '../api/types';
import { emptyState, type LoadState } from '../state';

export function useWorkspace() {
    const [selected, setSelected] = useState<PublicationReference | null>(null);
    const [detail, setDetail] = useState<LoadState<PublicationDetailResponse>>(emptyState);
    const detailRequest = useRef<AbortController | null>(null);
    const selectedId = useRef<string | null>(null);
    const [question, setQuestion] = useState('');
    const [answer, setAnswer] = useState<LoadState<AnswerResponse>>(emptyState);
    const answerRequest = useRef<AbortController | null>(null);
    const answerPending = useRef(false);
    const [evidence, setEvidence] = useState<LoadState<EvidenceResponse>>(emptyState);
    const [citation, setCitation] = useState<{ reference: EvidenceReference; key: string | undefined } | null>(null);
    const evidenceRequest = useRef<AbortController | null>(null);

    const selectPublication = useCallback(async (publication: PublicationReference) => {
        detailRequest.current?.abort();
        answerRequest.current?.abort(); answerPending.current = false;
        setAnswer(emptyState());
        evidenceRequest.current?.abort(); setEvidence(emptyState()); setCitation(null);
        selectedId.current = publication.publicationId;
        setSelected(publication);
        const controller = new AbortController(); detailRequest.current = controller;
        setDetail({ data: null, loading: true, error: null });
        try {
            const data = await api.publication(publication.publicationId, controller.signal);
            if (controller.signal.aborted || selectedId.current !== publication.publicationId) return;
            if (data.publication.publicationId !== publication.publicationId) throw new ApiError('INVALID_RESPONSE', 200);
            setDetail({ data, loading: false, error: null });
        } catch (error) {
            if (!controller.signal.aborted && !isCancellation(error)) setDetail({ data: null, loading: false, error: asApiError(error) });
        }
    }, []);

    const submit = useCallback(async (strategy: Strategy, limit: number) => {
        const publicationId = selectedId.current;
        if (!publicationId || detail.data?.publication.publicationId !== publicationId || answerPending.current || !validQuestion(question, limit)) return;
        answerPending.current = true;
        evidenceRequest.current?.abort(); setEvidence(emptyState()); setCitation(null);
        const controller = new AbortController(); answerRequest.current = controller;
        setAnswer({ data: null, loading: true, error: null });
        try {
            const data = await api.answer(publicationId, { question, retrievalStrategy: strategy }, controller.signal);
            if (controller.signal.aborted || selectedId.current !== publicationId || answerRequest.current !== controller) return;
            if (data.publication.publication.publicationId !== publicationId) throw new ApiError('INVALID_RESPONSE', 200);
            setAnswer({ data, loading: false, error: null });
        } catch (error) {
            if (!controller.signal.aborted && !isCancellation(error) && answerRequest.current === controller)
                setAnswer({ data: null, loading: false, error: asApiError(error) });
        } finally {
            if (answerRequest.current === controller) answerPending.current = false;
        }
    }, [question, detail.data]);

    const openEvidence = useCallback(async (reference: EvidenceReference, key?: string) => {
        if (reference.publicationId !== selectedId.current) return;
        evidenceRequest.current?.abort();
        const controller = new AbortController(); evidenceRequest.current = controller;
        setCitation({ reference, key }); setEvidence({ data: null, loading: true, error: null });
        try {
            const data = await api.evidence(reference.publicationId, reference.chunkId, controller.signal);
            if (controller.signal.aborted || evidenceRequest.current !== controller || selectedId.current !== reference.publicationId) return;
            if (data.evidence.publicationId !== reference.publicationId || data.evidence.chunkId !== reference.chunkId
                || data.evidence.snapshotId !== reference.snapshotId || data.evidence.commitSha !== reference.commitSha)
                throw new ApiError('INVALID_RESPONSE', 200);
            setEvidence({ data, loading: false, error: null });
        } catch (error) {
            if (!controller.signal.aborted && !isCancellation(error) && evidenceRequest.current === controller)
                setEvidence({ data: null, loading: false, error: asApiError(error) });
        }
    }, []);

    useEffect(() => () => { detailRequest.current?.abort(); answerRequest.current?.abort(); evidenceRequest.current?.abort(); }, []);
    return { selected, detail, selectPublication, question, setQuestion, answer, submit, evidence, citation, openEvidence };
}

// String.length counts UTF-16 code units, exactly like the Java request contract.
export function validQuestion(question: string, limit: number): boolean {
    if (!question.trim() || question.length > limit || question.includes('\0')) return false;
    for (const character of question) {
        const code = character.codePointAt(0)!;
        if (code >= 0xd800 && code <= 0xdfff) return false;
    }
    return true;
}
