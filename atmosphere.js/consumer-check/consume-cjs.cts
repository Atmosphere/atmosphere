// The .d.cts half: a CommonJS consumer resolving each entry's declarations
// THROUGH THE package.json `exports` MAP, by package name (self-reference).
//
// This used to require('../dist/index.cjs') — a relative path, which resolves the
// sibling .d.cts directly and never consults `exports` at all. That made the gate
// blind to the real defect: with a single top-level "types" ahead of the
// import/require conditions, require('atmosphere.js') under moduleResolution
// node16 resolved the ESM index.d.ts and failed TS1479/TS1541 while this file
// stayed green. Importing by name is what a consumer actually does.
import core = require('atmosphere.js');
import react = require('atmosphere.js/react');
import vue = require('atmosphere.js/vue');
import svelte = require('atmosphere.js/svelte');
import chat = require('atmosphere.js/chat');
import room = require('atmosphere.js/room');
import streaming = require('atmosphere.js/streaming');
import queue = require('atmosphere.js/queue');
import history = require('atmosphere.js/history');
import interactions = require('atmosphere.js/interactions');
import rn = require('atmosphere.js/react-native');

const req: core.AtmosphereRequest = { url: 'http://localhost:8080/chat', transport: 'websocket' };
export const sub = core.atmosphere.subscribe(req);
export const version: string = core.VERSION;
export const used = [
  react.useAtmosphere, vue.useAtmosphere, svelte.createAtmosphereStore, chat.ChatLayout,
  room.AtmosphereRooms, streaming.subscribeStreaming, queue.OfflineQueue,
  history.MessageHistorySync, interactions, rn.useAtmosphereRN, rn.setupReactNative,
];

declare const stats: streaming.SessionStats;
// @ts-expect-error SessionStats has no `totalTokens` — if this stops erroring the types went `any`.
export const bogus = stats.totalTokens;
