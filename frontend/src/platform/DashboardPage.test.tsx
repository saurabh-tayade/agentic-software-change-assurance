import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';

it('keeps the shared toolbar outside a named, keyboard-focusable body', async () => {
    const refresh = vi.fn();
    const { container } = render(<DashboardPage title="Repositories" description="Browse prepared versions."
        actions={<button onClick={refresh}>Refresh Catalog</button>}><p>Repository tiles</p></DashboardPage>);
    const body = screen.getByRole('region', { name: 'Repositories content' });
    const header = container.querySelector('.dashboard-header')!;
    expect(header.nextElementSibling).toBe(body);
    expect(within(body).queryByRole('heading', { name: 'Repositories' })).not.toBeInTheDocument();
    expect(body).toHaveAttribute('tabindex', '0');
    const user = userEvent.setup();
    await user.tab(); expect(screen.getByRole('button')).toHaveFocus();
    await user.keyboard('{Enter}'); expect(refresh).toHaveBeenCalledTimes(1);
    await user.tab(); expect(body).toHaveFocus();
});
