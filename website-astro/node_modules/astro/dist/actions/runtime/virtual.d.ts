import type { ActionClient } from './server.js';
export * from 'virtual:astro:actions/runtime';
export declare function getActionPath(action: ActionClient<any, any, any>): string;
export declare const actions: Record<string | symbol, any>;
