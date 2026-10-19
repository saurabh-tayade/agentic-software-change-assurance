import { createContext, useContext } from 'react';
import type { HelpTopicId } from './topics';
export const HelpContext = createContext<(topic: HelpTopicId, opener?: HTMLElement | null) => void>(() => {});
export const useHelp = () => useContext(HelpContext);
