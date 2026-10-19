import { useEffect, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { guidance, type GuidanceId } from './topics';
import { useHelp } from './HelpContext';
export function ContextHelp({ about }: { about: GuidanceId }) {
    const [open, setOpen] = useState(false);
    useEffect(() => {
        if (!open) return;
        const close = () => setOpen(false);
        window.addEventListener('hashchange', close);
        return () => window.removeEventListener('hashchange', close);
    }, [open]);
    const trigger = useRef<HTMLButtonElement>(null);
    const openHelp = useHelp();
    const item = guidance[about];
    return <Popover.Root open={open} onOpenChange={setOpen}>
        <Popover.Trigger asChild><button type="button" className="info-button" ref={trigger} aria-label={`About ${item.title}`}>i</button></Popover.Trigger>
        <Popover.Portal><Popover.Content className="context-popover" sideOffset={8} collisionPadding={16} aria-label={item.title}>
            <strong>{item.title}</strong><p>{item.text}</p>
            <button type="button" className="text-button" onClick={() => { setOpen(false); openHelp(item.topic, trigger.current); }}>Read more in Help</button>
            <Popover.Close asChild><button type="button" className="icon-button" aria-label="Close explanation">×</button></Popover.Close>
            <Popover.Arrow className="popover-arrow" />
        </Popover.Content></Popover.Portal>
    </Popover.Root>;
}
