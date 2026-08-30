// Consumer-side proof that the shipped declarations resolve through the
// package.json `exports` map (self-name resolution) and carry real types.
import { atmosphere, Atmosphere, VERSION } from 'atmosphere.js';
import type { AtmosphereRequest } from 'atmosphere.js';
import { useAtmosphere } from 'atmosphere.js/react';
import { useAtmosphere as useAtmosphereVue } from 'atmosphere.js/vue';
import { createAtmosphereStore } from 'atmosphere.js/svelte';
import { ChatLayout } from 'atmosphere.js/chat';
import { AtmosphereRooms } from 'atmosphere.js/room';
import { subscribeStreaming } from 'atmosphere.js/streaming';
import type { SessionStats } from 'atmosphere.js/streaming';
import { OfflineQueue } from 'atmosphere.js/queue';
import { MessageHistorySync } from 'atmosphere.js/history';
import { useAtmosphereRN, setupReactNative } from 'atmosphere.js/react-native';
import * as interactions from 'atmosphere.js/interactions';

const req: AtmosphereRequest = { url: 'http://localhost:8080/chat', transport: 'websocket' };
const a: Atmosphere = atmosphere;
export const version: string = VERSION;
export const sub = a.subscribe(req);
export const used = [
  useAtmosphere, useAtmosphereVue, createAtmosphereStore, ChatLayout, AtmosphereRooms,
  subscribeStreaming, OfflineQueue, MessageHistorySync, useAtmosphereRN, setupReactNative,
  interactions,
];

// The bug check-export-types.mjs exists for: a missing .d.ts degrades the entry
// to `any` and a bogus field typechecks. This must be an error, not `any`.
declare const stats: SessionStats;
// @ts-expect-error SessionStats has no `totalTokens` — if this stops erroring the types went `any`.
export const bogus = stats.totalTokens;
