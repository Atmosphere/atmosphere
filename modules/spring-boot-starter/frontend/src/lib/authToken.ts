/**
 * Shared resolver for the Atmosphere auth token used by the console.
 *
 * Token source (single source of truth for both the WebSocket and the admin
 * REST surface): a `?token=` URL parameter — persisted to localStorage so it
 * survives reconnects and SPA navigation — else a previously stored token,
 * else none (the console stays anonymous, exactly as before).
 *
 * The server's `AdminApiAuthFilter` / `TokenValidator` reads this token from
 * the `X-Atmosphere-Auth` header (REST) or query parameter (WebSocket) and
 * resolves it to a principal, which the admin write-guard requires.
 */
export function resolveAuthToken(): string | null {
  try {
    const fromUrl = new URLSearchParams(window.location.search).get('token')
    if (fromUrl) {
      localStorage.setItem('atmosphere-auth-token', fromUrl)
      return fromUrl
    }
    return localStorage.getItem('atmosphere-auth-token')
  } catch {
    // Storage unavailable (private mode, sandbox) — treat as anonymous.
    return null
  }
}

/**
 * Merge the `X-Atmosphere-Auth` header into a header bag when a token is
 * available. Returns {@code base} unchanged when anonymous, so callers send
 * exactly what they sent before on hosts with no auth configured.
 */
export function authHeaders(base: Record<string, string> = {}): Record<string, string> {
  const token = resolveAuthToken()
  return token ? { ...base, 'X-Atmosphere-Auth': token } : base
}
