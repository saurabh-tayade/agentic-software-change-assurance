import { useEffect, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';

const examples = [
    'What capabilities does this repository provide?',
    'How is shipping cost calculated?',
    'Where is input validation performed?',
    'Which tests cover remote-destination pricing?',
    'Does this application support currency conversion?',
];
export function ExampleQuestions({ active, disabled, onSelect }: { active: boolean; disabled: boolean; onSelect: (question: string) => void }) {
    const [open, setOpen] = useState(false);
    const selected = useRef(false);
    useEffect(() => { if (!active || disabled) setOpen(false); }, [active, disabled]);
    return <Popover.Root open={open && active && !disabled} onOpenChange={value => { selected.current = false; setOpen(value); }}>
        <Popover.Trigger asChild><button type="button" className="examples-trigger" disabled={disabled}>Example questions <span aria-hidden="true">?</span></button></Popover.Trigger>
        <Popover.Portal><Popover.Content className="context-popover examples-popover" align="end" sideOffset={8} collisionPadding={16}
            aria-label="Example questions" onCloseAutoFocus={event => {
                if (selected.current) { event.preventDefault(); document.getElementById('question')?.focus(); }
                else if (!active) event.preventDefault();
            }}>
            <strong>Example questions</strong><p>Choose a question to edit. Nothing is submitted automatically.</p>
            <ul>{examples.map(example => <li key={example}><button type="button" className="example-button" onClick={() => {
                selected.current = true; onSelect(example); setOpen(false);
            }}>{example}</button></li>)}</ul>
            <Popover.Close asChild><button type="button" className="icon-button" aria-label="Close example questions">×</button></Popover.Close>
        </Popover.Content></Popover.Portal>
    </Popover.Root>;
}
