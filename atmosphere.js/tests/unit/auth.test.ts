import { describe, it, expect, vi } from 'vitest';
import { AtmosphereProtocol } from '../../src/utils/protocol';
import type { AtmosphereRequest } from '../../src/types';

describe('Authentication', () => {
  describe('protocol.buildUrl with authToken', () => {
    it('should include X-Atmosphere-Auth when authToken is set on request', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        authToken: 'my-secret-token',
      };
      const url = protocol.buildUrl(request);
      expect(url).toContain('X-Atmosphere-Auth=my-secret-token');
    });

    it('should use protocol authToken when request authToken is not set', () => {
      const protocol = new AtmosphereProtocol();
      protocol.authToken = 'stored-token';
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
      };
      const url = protocol.buildUrl(request);
      expect(url).toContain('X-Atmosphere-Auth=stored-token');
    });

    it('should prefer request authToken over protocol authToken', () => {
      const protocol = new AtmosphereProtocol();
      protocol.authToken = 'old-token';
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        authToken: 'new-token',
      };
      const url = protocol.buildUrl(request);
      expect(url).toContain('X-Atmosphere-Auth=new-token');
      expect(url).not.toContain('old-token');
    });

    it('should not include X-Atmosphere-Auth when no token is set', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
      };
      const url = protocol.buildUrl(request);
      expect(url).not.toContain('X-Atmosphere-Auth');
    });

    it('should URL-encode the auth token', () => {
      const protocol = new AtmosphereProtocol();
      const request: AtmosphereRequest = {
        url: 'http://localhost/test',
        transport: 'websocket',
        authToken: 'token with spaces & specials=yes',
      };
      const url = protocol.buildUrl(request);
      expect(url).toContain('X-Atmosphere-Auth=token%20with%20spaces%20%26%20specials%3Dyes');
    });
  });

  describe('protocol.extractAuthHeaders', () => {
    it('should extract refresh token from headers', () => {
      const protocol = new AtmosphereProtocol();
      const headers = new Map([['X-Atmosphere-Auth-Refresh', 'new-token-123']]);
      const result = protocol.extractAuthHeaders((name) => headers.get(name) ?? null);
      expect(result.refreshToken).toBe('new-token-123');
      expect(result.expired).toBeUndefined();
      expect(protocol.authToken).toBe('new-token-123');
    });

    it('should extract expired reason from headers', () => {
      const protocol = new AtmosphereProtocol();
      const headers = new Map([['X-Atmosphere-Auth-Expired', 'Token expired']]);
      const result = protocol.extractAuthHeaders((name) => headers.get(name) ?? null);
      expect(result.expired).toBe('Token expired');
      expect(result.refreshToken).toBeUndefined();
    });

    it('should return empty when no auth headers present', () => {
      const protocol = new AtmosphereProtocol();
      const result = protocol.extractAuthHeaders(() => null);
      expect(result.refreshToken).toBeUndefined();
      expect(result.expired).toBeUndefined();
    });
  });
});
