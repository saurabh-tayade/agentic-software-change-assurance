import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { AnswerPanel } from './AnswerPanel';
import { answer, details, limited } from '../test/fixtures';
import type { AnswerStatus } from '../api/types';

it.each<AnswerStatus>(['SUPPORTED', 'PARTIALLY_SUPPORTED', 'INSUFFICIENT_EVIDENCE'])('keeps the %s preview minimal without changing the original response', async status => {
    const user = userEvent.setup();
    const onFullAnswer = vi.fn();
    const original = { ...answer, status, publication: details(limited) };
    render(<AnswerPanel answer={original} preview onFullAnswer={onFullAnswer} />);
    const preview = screen.getByRole('article', { name: 'Answer' });
    expect(within(preview).getByText(status.replaceAll('_', ' ').toLowerCase())).toBeVisible();
    expect(preview.querySelector('.answer-text')?.textContent).toBe(original.answer);
    expect(within(preview).queryByRole('button', { name: /Open citation/ })).not.toBeInTheDocument();
    expect(within(preview).queryByText(/uncertainty|processing|correctness|validated/i)).not.toBeInTheDocument();
    const more = within(preview).getByRole('button', { name: 'View full answer' });
    expect(within(preview).getAllByRole('button')).toEqual([more]);
    more.focus(); await user.keyboard('{Enter}');
    expect(onFullAnswer).toHaveBeenCalledTimes(1);
    expect(original.citations).toEqual(answer.citations);
    expect(original.uncertainties).toEqual(answer.uncertainties);
});

it('keeps every reported uncertainty and coverage summary visible while disclosing general policies', async () => {
    const user = userEvent.setup();
    const uncertainties = [...answer.uncertainties, 'Publication processing is PARTIAL; repository evidence is incomplete.'];
    render(<AnswerPanel answer={{ ...answer, publication: details(limited), uncertainties }} />);
    const region = screen.getByRole('region', { name: 'Uncertainty and limitations' });
    for (const text of uncertainties) expect(within(region).getByText(text)).toBeVisible();
    expect(within(region).getByText(/PARTIAL processing; evidence coverage is incomplete/)).toBeVisible();
    expect(within(region).getByText(/1 retrieved excerpt\(s\) omitted/)).toBeVisible();
    const disclosure = within(region).getByText('General ingestion & processing limitations (2)');
    expect(disclosure.closest('details')).not.toHaveAttribute('open');
    expect(within(region).getByText('Committed source only.')).not.toBeVisible();
    await user.click(disclosure);
    expect(disclosure.closest('details')).toHaveAttribute('open');
    expect(within(region).getByText('Committed source only.')).toBeVisible();
    expect(within(region).getByText('Chunk budget reached.')).toBeVisible();
    for (const text of uncertainties) expect(within(region).getByText(text)).toBeVisible();
});

it('does not show an empty policy disclosure or imply certainty when none was reported', () => {
    render(<AnswerPanel answer={{ ...answer, uncertainties: [],
        publication: { ...details(), ingestionLimitations: [], processingLimitations: [] } }} />);
    expect(screen.queryByText(/General ingestion & processing limitations/)).not.toBeInTheDocument();
    expect(screen.getByText(/No additional uncertainties were reported. This is not a guarantee/)).toBeVisible();
});

it.each([0, 1, 2])('describes validation accurately for %i citations and preserves the backend statement', async count => {
    const user = userEvent.setup();
    const citations = Array.from({ length: count }, (_, i) => ({ ...answer.citations[0]!, key: `E${i + 1}` }));
    render(<AnswerPanel answer={{ ...answer, claims: [], citations }} />);
    if (count) {
        expect(screen.getByText('Citation identities validated')).toBeVisible();
        expect(screen.getByText('Source identity checked; the answer’s interpretation has not been independently verified.')).toBeVisible();
        expect(screen.queryByText('No citations returned.')).not.toBeInTheDocument();
    } else {
        expect(screen.getByText('No citations returned.')).toBeVisible();
        expect(screen.queryByText('Citation identities validated')).not.toBeInTheDocument();
        expect(screen.getByText('Factual correctness has not been independently verified.')).toBeVisible();
    }
    await user.click(screen.getByText('Retrieval & execution details'));
    expect(screen.getByText(answer.validation)).toBeVisible();
});
