import type { ComponentProps } from 'react';
import { Modal } from '../platform/Modal';
import { EvidenceExplorer } from './EvidenceExplorer';
interface Props extends ComponentProps<typeof EvidenceExplorer> {
    open: boolean; onClose: () => void; restoreFocus: () => void;
}
export function SupportingSourcesDrawer({ open, onClose, restoreFocus, ...evidence }: Props) {
    return <Modal open={open} onOpenChange={value => { if (!value) onClose(); }} title="Supporting Sources"
        description="Original persisted excerpts, line numbers, and source provenance." className="sources-sheet"
        onInitialFocus={() => document.getElementById('evidence-title')?.focus()} onRestoreFocus={restoreFocus}>
        <EvidenceExplorer {...evidence} inSheet />
    </Modal>;
}
