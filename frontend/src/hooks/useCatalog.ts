import { useCallback, useEffect, useRef, useState } from 'react';
import { api, asApiError, isCancellation } from '../api/client';
import type { CapabilitiesResponse, PublicationListResponse } from '../api/types';
import { emptyState, type LoadState } from '../state';

export function useCatalog() {
    const [capabilities, setCapabilities] = useState<LoadState<CapabilitiesResponse>>({ ...emptyState(), loading: true });
    const [catalog, setCatalog] = useState<LoadState<PublicationListResponse>>({ ...emptyState(), loading: true });
    const pageRequest = useRef<AbortController | null>(null);
    const capabilitiesRequest = useRef<AbortController | null>(null);

    const loadCapabilities = useCallback(async () => {
        capabilitiesRequest.current?.abort();
        const controller = new AbortController(); capabilitiesRequest.current = controller;
        setCapabilities({ data: null, loading: true, error: null });
        try {
            const data = await api.capabilities(controller.signal);
            if (!controller.signal.aborted) setCapabilities({ data, loading: false, error: null });
        } catch (error) {
            if (!controller.signal.aborted && !isCancellation(error)) setCapabilities({ data: null, loading: false, error: asApiError(error) });
        }
    }, []);

    const loadPage = useCallback(async (after: string | null = null) => {
        pageRequest.current?.abort();
        const controller = new AbortController(); pageRequest.current = controller;
        setCatalog(previous => ({ ...previous, loading: true, error: null }));
        try {
            const page = await api.publications(controller.signal, after);
            if (controller.signal.aborted) return;
            setCatalog(previous => {
                const items = after ? [...(previous.data?.items ?? []), ...page.items] : page.items;
                const unique = [...new Map(items.map(item => [item.publicationId, item])).values()];
                return { data: { ...page, items: unique }, loading: false, error: null };
            });
        } catch (error) {
            if (!controller.signal.aborted && !isCancellation(error)) setCatalog(previous => ({ ...previous, loading: false, error: asApiError(error) }));
        }
    }, []);

    useEffect(() => {
        void loadCapabilities(); void loadPage();
        return () => { pageRequest.current?.abort(); capabilitiesRequest.current?.abort(); };
    }, [loadCapabilities, loadPage]);
    return { capabilities, catalog, loadPage, loadCapabilities };
}
