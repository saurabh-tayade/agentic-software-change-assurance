import { useRef, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
interface Props {
    open: boolean; onOpenChange: (open: boolean) => void; title: string; description: string;
    children: ReactNode; className?: string; onRestoreFocus?: () => void; onInitialFocus?: () => void;
}
/** Radix owns modal semantics, focus trapping, Escape dismissal, and inert background. */
export function Modal({ open, onOpenChange, title, description, children, className = '', onRestoreFocus, onInitialFocus }: Props) {
    const opener = useRef<HTMLElement | null>(null);
    return <Dialog.Root open={open} onOpenChange={onOpenChange}>
        <Dialog.Portal><Dialog.Overlay className="modal-overlay" />
            <Dialog.Content className={`modal-content ${className}`}
                onOpenAutoFocus={event => {
                    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
                    if (onInitialFocus) { event.preventDefault(); onInitialFocus(); }
                }}
                onCloseAutoFocus={event => {
                    event.preventDefault();
                    if (onRestoreFocus) onRestoreFocus();
                    else if (opener.current?.isConnected) opener.current.focus();
                    else document.getElementById('global-help')?.focus();
                }}>
                <div className="modal-header"><div><Dialog.Title>{title}</Dialog.Title>
                    <Dialog.Description>{description}</Dialog.Description></div>
                    <Dialog.Close asChild><button className="icon-button" aria-label={`Close ${title}`}>×</button></Dialog.Close></div>
                <div className="modal-body">{children}</div>
            </Dialog.Content></Dialog.Portal>
    </Dialog.Root>;
}
