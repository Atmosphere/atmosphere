/**
 * Minimal type declarations for react-native and @react-native-community/netinfo.
 * These allow the react-native hooks to use top-level imports without
 * requiring @types/react-native as a devDependency.
 */

declare module 'react-native' {
  type AppStateStatus = 'active' | 'background' | 'inactive' | 'unknown' | 'extension';

  interface AppStateStatic {
    addEventListener(
      type: string,
      listener: (state: AppStateStatus) => void,
    ): { remove(): void };
  }

  export const AppState: AppStateStatic;
}

declare module '@react-native-community/netinfo' {
  interface NetInfoState {
    isConnected: boolean | null;
    isInternetReachable: boolean | null;
  }

  function addEventListener(
    listener: (state: NetInfoState) => void,
  ): () => void;

  export default { addEventListener };
  export type { NetInfoState };
}
