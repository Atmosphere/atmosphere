/**
 * Validates that a pathname is not multi-level encoded.
 * Detects if a pathname contains encoding that was encoded again (e.g., %2561dmin where %25 decodes to %).
 * This prevents double/triple encoding bypasses of security checks.
 *
 * @param pathname - The pathname to validate
 * @returns The decoded pathname if valid
 * @throws Error if multi-level encoding is detected
 */
export declare function validateAndDecodePathname(pathname: string): string;
