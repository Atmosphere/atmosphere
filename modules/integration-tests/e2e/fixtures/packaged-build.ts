import { existsSync, readdirSync, readFileSync } from 'fs';
import { delimiter, resolve } from 'path';

export const ROOT = resolve(__dirname, '..', '..', '..', '..');

let reactorVersionCache: string | undefined;

/** The reactor version from the root pom.xml, e.g. 4.0.71-SNAPSHOT. */
export function reactorVersion(): string {
  if (reactorVersionCache === undefined) {
    const pom = readFileSync(resolve(ROOT, 'pom.xml'), 'utf8');
    const m = /<version>([^<]+)<\/version>/.exec(pom);
    if (!m || !/^\d+\.\d+\.\d+(-SNAPSHOT)?$/.test(m[1])) {
      throw new Error(`Cannot read the reactor version from ${resolve(ROOT, 'pom.xml')}`);
    }
    reactorVersionCache = m[1];
  }
  return reactorVersionCache;
}

/**
 * Runtime classpath of a module whose build copied its runtime dependencies into
 * target/e2e-lib (maven-dependency-plugin copy-dependencies, bound to package).
 *
 * These servers used to boot with `./mvnw exec:java`, which put Maven Central on the
 * critical path of every test. The CI E2E job restores a Maven cache but never runs a
 * build, and `exec:java` is a plugin PREFIX: Maven resolves the descriptor of every
 * plugin declared in the project until one answers to "exec" — release, eclipse,
 * m2e-lifecycle — none of which `install -DskipTests` ever downloads. Each boot therefore
 * fetched (or re-verified) artifacts from a live Central, and one HTTP 429 became an opaque
 * "port not ready" timeout (ai-ux-flows SB3, run 33464934108; grpc-browser before it).
 * Offline mode was tried and reverted (3b6373a7c7): the plugins were never in the cache
 * to begin with. A classpath the build materialised is the only boot path with no
 * resolver in it.
 *
 * Absent or stale is a hard failure that names the build command, never a fallback to
 * Maven. target/e2e-lib is not cleaned between builds, so a version bump without `clean`
 * leaves two atmosphere versions side by side — a wildcard classpath would then load
 * whichever the JVM lists first, so mixed versions are rejected too.
 */
export function runtimeClasspath(modulePath: string): string {
  const moduleDir = resolve(ROOT, modulePath);
  const classes = resolve(moduleDir, 'target', 'classes');
  const lib = resolve(moduleDir, 'target', 'e2e-lib');
  const build = `./mvnw clean install -pl ${modulePath} -DskipTests`;
  if (!existsSync(classes) || !existsSync(lib)) {
    throw new Error(`${modulePath} is not packaged (missing target/classes or target/e2e-lib). Run: ${build}`);
  }
  const version = reactorVersion();
  const atmosphere = readdirSync(lib).filter((f) => f.startsWith('atmosphere-') && f.endsWith('.jar'));
  const stale = atmosphere.filter((f) => !f.endsWith(`-${version}.jar`));
  if (atmosphere.length === 0 || stale.length > 0) {
    throw new Error(
      `${modulePath}/target/e2e-lib is stale — expected only ${version} atmosphere jars ` +
        `(found: ${atmosphere.join(', ') || 'nothing'}). Run: ${build}`,
    );
  }
  return [classes, resolve(lib, '*')].join(delimiter);
}
