// The .d.cts half: a CommonJS consumer resolving each entry's declarations
// through the .cjs bundles (dist/x.cjs -> dist/x.d.cts).
import core = require('../dist/index.cjs');
import react = require('../dist/react.cjs');
import vue = require('../dist/vue.cjs');
import svelte = require('../dist/svelte.cjs');
import chat = require('../dist/chat.cjs');
import room = require('../dist/room.cjs');
import streaming = require('../dist/streaming.cjs');
import queue = require('../dist/queue.cjs');
import history = require('../dist/history.cjs');
import interactions = require('../dist/interactions.cjs');
import rn = require('../dist/react-native.cjs');

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
