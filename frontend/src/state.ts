import type { ApiError } from './api/client';

export interface LoadState<T> { data: T | null; loading: boolean; error: ApiError | null }
export const emptyState = <T>(): LoadState<T> => ({ data: null, loading: false, error: null });
