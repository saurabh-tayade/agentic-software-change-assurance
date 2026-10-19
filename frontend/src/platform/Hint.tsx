import type { ReactElement } from 'react';
import * as Tooltip from '@radix-ui/react-tooltip';
export function Hint({ text, children }: { text: string; children: ReactElement }) {
    return <Tooltip.Provider delayDuration={300}><Tooltip.Root><Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
        <Tooltip.Portal><Tooltip.Content className="tooltip" sideOffset={8}>{text}<Tooltip.Arrow /></Tooltip.Content></Tooltip.Portal>
    </Tooltip.Root></Tooltip.Provider>;
}
