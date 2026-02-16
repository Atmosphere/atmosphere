import { describe, it, expect } from 'vitest';
import { AtmosphereProtocol } from '../../src/utils/protocol';
import type { AtmosphereRequest } from '../../src/types';

describe('AtmosphereProtocol', () => {
  describe('handleProtocol', () => {
    it('should extract UUID and heartbeat from handshake', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        enableProtocol: true,
        messageDelimiter: '|',
      };

      const result = protocol.handleProtocol(request, 'abc-123|60000|X|');

      expect(result.wasHandshake).toBe(true);
      expect(result.message).toBe('');
      expect(protocol.uuid).toBe('abc-123');
      expect(protocol.heartbeatInterval).toBe(60000);
      expect(protocol.heartbeatPadding).toBe('X');
    });

    it('should extract trailing messages after handshake', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        enableProtocol: true,
        messageDelimiter: '|',
      };

      const result = protocol.handleProtocol(request, 'uuid-1|5000|X|hello|world');

      expect(result.wasHandshake).toBe(true);
      expect(result.message).toBe('hello|world');
    });

    it('should pass through messages after first handshake', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        enableProtocol: true,
        messageDelimiter: '|',
      };

      // First message = handshake
      protocol.handleProtocol(request, 'uuid|5000|X|');

      // Second message = regular
      const result = protocol.handleProtocol(request, 'regular message');
      expect(result.wasHandshake).toBe(false);
      expect(result.message).toBe('regular message');
    });

    it('should skip protocol for polling transport', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'polling',
        enableProtocol: true,
        messageDelimiter: '|',
      };

      const result = protocol.handleProtocol(request, 'uuid|5000|X|');
      expect(result.wasHandshake).toBe(false);
      expect(result.message).toBe('uuid|5000|X|');
    });
  });

  describe('processMessage with trackMessageLength', () => {
    it('should parse length-delimited messages', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        trackMessageLength: true,
        messageDelimiter: '|',
      };

      const result = protocol.processMessage('5|hello5|world', request);

      expect(result).not.toBeNull();
      expect(result!.messages).toEqual(['hello', 'world']);
    });

    it('should buffer partial messages', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        trackMessageLength: true,
        messageDelimiter: '|',
      };

      // Send partial message: length says 12, but only 3 chars arrive
      const result1 = protocol.processMessage('12|hel', request);
      expect(result1).toBeNull();

      // Send more but not enough (total: 3+7=10, need 12)
      const result2 = protocol.processMessage('lo worl', request);
      expect(result2).toBeNull();

      // Send final 2 chars to complete
      const result3 = protocol.processMessage('d!', request);
      expect(result3).not.toBeNull();
      expect(result3!.messages).toEqual(['hello world!']);
    });

    it('should throw on non-numeric length', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        trackMessageLength: true,
        messageDelimiter: '|',
      };

      expect(() => protocol.processMessage('abc|data', request)).toThrow(
        'Message length "abc" is not a number',
      );
    });
  });

  describe('processMessage without trackMessageLength', () => {
    it('should pass messages through directly', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
      };

      const result = protocol.processMessage('hello world', request);
      expect(result).not.toBeNull();
      expect(result!.messages).toEqual(['hello world']);
    });
  });

  describe('buildUrl', () => {
    it('should attach Atmosphere framework headers as query params', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        enableProtocol: true,
        trackMessageLength: true,
        headers: { Authorization: 'Bearer token123' },
      };

      const url = protocol.buildUrl(request);

      expect(url).toContain('X-Atmosphere-Transport=websocket');
      expect(url).toContain('X-Atmosphere-Framework=');
      expect(url).toContain('X-Atmosphere-tracking-id=');
      expect(url).toContain('X-atmo-protocol=true');
      expect(url).toContain('X-Atmosphere-TrackMessageSize=true');
      expect(url).toContain('Authorization=Bearer%20token123');
    });
  });

  describe('heartbeat', () => {
    it('should start and stop heartbeat', () => {
      vi.useFakeTimers();
      const protocol = new AtmosphereProtocol();
      const pushFn = vi.fn();

      protocol.heartbeatInterval = 1000;
      protocol.heartbeatPadding = 'X';
      protocol.setPushFunction(pushFn);

      protocol.startHeartbeat();

      vi.advanceTimersByTime(1000);
      expect(pushFn).toHaveBeenCalledWith('X');
      expect(pushFn).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(1000);
      expect(pushFn).toHaveBeenCalledTimes(2);

      protocol.stopHeartbeat();
      vi.advanceTimersByTime(5000);
      expect(pushFn).toHaveBeenCalledTimes(2); // No more calls

      vi.useRealTimers();
    });
  });

  describe('reset', () => {
    it('should reset protocol state', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        enableProtocol: true,
        messageDelimiter: '|',
      };

      // First handshake
      protocol.handleProtocol(request, 'uuid|5000|X|');
      expect(protocol.uuid).toBe('uuid');

      // Reset
      protocol.reset();

      // Should treat next message as handshake again
      const result = protocol.handleProtocol(request, 'new-uuid|3000|Y|');
      expect(result.wasHandshake).toBe(true);
      expect(protocol.uuid).toBe('new-uuid');
    });
  });
});
