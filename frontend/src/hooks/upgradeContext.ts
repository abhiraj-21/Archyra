import { createContext, useContext } from 'react';

export interface UpgradeValue {
  /** Opens the upgrade dialog, naming the feature that asked for it. */
  openUpgrade: (feature?: string) => void;
}

export const UpgradeContext = createContext<UpgradeValue>({ openUpgrade: () => {} });
export const useUpgrade = () => useContext(UpgradeContext);
