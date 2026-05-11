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

  // Minimal stubs for ConnectionStatusBadgeRN — full surface lives in @types/react-native
  // (not a devDependency here; consumers' RN bundler resolves the real package at build time).
  export type ViewStyle = Record<string, unknown>;
  export type TextStyle = Record<string, unknown>;
  export const View: import('react').ComponentType<{ style?: ViewStyle; testID?: string; children?: import('react').ReactNode }>;
  export const Text: import('react').ComponentType<{ style?: TextStyle; testID?: string; children?: import('react').ReactNode }>;
  export const StyleSheet: {
    create<T extends Record<string, ViewStyle | TextStyle>>(styles: T): T;
  };
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
