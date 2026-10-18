import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { EvidenceExplorer } from './EvidenceExplorer';
import { evidence } from '../test/fixtures';

it('preserves CRLF, blank lines, and an unterminated last line while numbering the original range', () => {
    const content = 'first\r\n\r\nthird\r\nfourth';
    render(<EvidenceExplorer state={{ data: { ...evidence, content }, loading: false, error: null }}
        citation={{ reference: evidence.evidence, key: 'E5' }} onRetry={() => {}} />);
    const source = screen.getByRole('region', { name: 'Original source, lines 4 to 7' });
    expect(Array.from(source.querySelectorAll('.source-line > span:last-child'), node => node.textContent).join('')).toBe(content);
    expect(Array.from(source.querySelectorAll('.line-number'), node => node.textContent)).toEqual(['4', '5', '6', '7']);
});
