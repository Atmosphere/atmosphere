/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, it, expect } from 'vitest';
import { roomMemberId } from '../../src';

/**
 * The client computes the same room member id as the server
 * (org.atmosphere.ai.RoomMemberIds, pinned by RoomMemberIdsTest with the same
 * vectors): a mismatch makes every tab show its own prompt twice.
 */
describe('roomMemberId', () => {
  it('matches the server for a tracking id', () => {
    expect(roomMemberId('a34c8569-22e2-4ede-939b-8172cd7a5e31')).toBe('e634ac1f6f9dcf74f80c3fac3161a9d6');
    expect(roomMemberId('me')).toBe('08f7d7455edbd74a611646548809f1e8');
  });

  it('hashes UTF-8 input longer than one SHA-256 block', () => {
    expect(roomMemberId('é'.repeat(40))).toBe('0eb6fc24c06d907e085d77ae982004a8');
  });

  it('has no id for an unknown tracking id', () => {
    expect(roomMemberId('0')).toBe('');
    expect(roomMemberId(undefined)).toBe('');
  });
});
