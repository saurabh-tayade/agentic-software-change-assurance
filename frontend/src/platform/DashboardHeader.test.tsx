import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { DashboardHeader } from './DashboardHeader';

it('uses a content heading and description without an empty actions container', () => {
    const { container } = render(<DashboardHeader title="Overview" description="Browse available capabilities." />);
    expect(screen.getByRole('heading', { level: 2, name: 'Overview' })).toBeVisible();
    expect(screen.getByText('Browse available capabilities.')).toBeVisible();
    expect(container.querySelector('.dashboard-header-actions')).toBeNull();
});

it('supports a compact header with a keyboard-accessible contextual action', async () => {
    const onRefresh = vi.fn();
    const { container } = render(<DashboardHeader compact title="Repositories" description="Browse prepared versions."
        actions={<button onClick={onRefresh}>Refresh Catalog</button>} />);
    expect(container.querySelector('header')).toHaveClass('dashboard-header-compact');
    const button = screen.getByRole('button', { name: 'Refresh Catalog' });
    button.focus(); await userEvent.setup().keyboard('{Enter}');
    expect(onRefresh).toHaveBeenCalledTimes(1);
});
