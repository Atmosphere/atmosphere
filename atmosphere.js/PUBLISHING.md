# Publishing atmosphere.js to NPM

## Prerequisites

1. **NPM Account**: You need access to the `@atmosphere` organization on npm
   - Create account at: https://www.npmjs.com/signup
   - Request access to `@atmosphere` org or use `atmosphere-client` as package name

2. **NPM Authentication**:
   ```bash
   npm login
   # Enter your npm credentials
   npm whoami  # Verify login
   ```

## Publishing Steps

### 1. Pre-publish Checks

```bash
cd atmosphere.js

# Clean install dependencies
rm -rf node_modules package-lock.json
npm install

# Run all checks
npm run type-check   # TypeScript compilation check
npm run lint         # Code quality check
npm run test:ci      # Run tests with coverage
npm run build        # Build all distribution formats

# Verify built files
ls -lh dist/
# Should contain:
# - index.js (ESM)
# - index.cjs (CommonJS)
# - index.global.js (IIFE for browsers)
# - index.d.ts (TypeScript types)
# - *.map files
```

### 2. Version Bump

```bash
# For alpha/beta releases
npm version prerelease --preid=alpha
# Results in: 5.0.0-alpha.2

# For release candidates
npm version prerelease --preid=rc
# Results in: 5.0.0-rc.1

# For stable releases
npm version patch   # 5.0.0 -> 5.0.1
npm version minor   # 5.0.0 -> 5.1.0
npm version major   # 5.0.0 -> 6.0.0
```

### 3. Test Package Locally

```bash
# Create tarball
npm pack

# Install in test project
cd /tmp
mkdir test-atmosphere
cd test-atmosphere
npm init -y
npm install /path/to/atmosphere-client-5.0.0-alpha.1.tgz

# Test imports
node -e "const atm = require('@atmosphere/client'); console.log(atm)"
```

### 4. Publish to NPM

```bash
cd atmosphere.js

# Dry run to see what will be published
npm publish --dry-run

# Publish alpha version
npm publish --tag alpha --access public

# Publish stable version (when ready)
npm publish --access public
```

### 5. Verify Publication

```bash
# Check on npm
open https://www.npmjs.com/package/@atmosphere/client

# Test installation
npm view @atmosphere/client
npm install @atmosphere/client@alpha
```

## Distribution Channels

### NPM Registry
- Published at: https://www.npmjs.com/package/@atmosphere/client
- Install: `npm install @atmosphere/client`
- CDN (auto): https://unpkg.com/@atmosphere/client@5.0.0/dist/index.global.js
- CDN (auto): https://cdn.jsdelivr.net/npm/@atmosphere/client@5.0.0/dist/index.global.js

### Maven Central (Optional)
Package atmosphere.js in Maven artifact for Java developers:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-javascript</artifactId>
    <version>4.0.0</version>
</dependency>
```

## Usage Examples

### Browser (CDN)
```html
<script src="https://unpkg.com/@atmosphere/client@5/dist/index.global.js"></script>
<script>
  const client = await atmosphere.subscribe(config, handlers);
</script>
```

### ES Modules
```javascript
import { subscribe } from '@atmosphere/client';

const client = await subscribe(config, handlers);
```

### CommonJS
```javascript
const { subscribe } = require('@atmosphere/client');

const client = await subscribe(config, handlers);
```

### TypeScript
```typescript
import { subscribe, AtmosphereConfig, AtmosphereHandlers } from '@atmosphere/client';

const config: AtmosphereConfig = {
  url: 'ws://localhost:8080/chat',
  transport: 'websocket'
};

const handlers: AtmosphereHandlers = {
  open: (response) => console.log('Connected'),
  message: (response) => console.log('Message:', response.responseBody)
};

const client = await subscribe(config, handlers);
```

## Release Workflow

### Alpha Releases (5.0.0-alpha.x)
- For early testing
- Breaking changes allowed
- Tag: `@alpha`
- Install: `npm install @atmosphere/client@alpha`

### Beta Releases (5.0.0-beta.x)
- Feature complete
- API stabilizing
- Tag: `@beta`
- Install: `npm install @atmosphere/client@beta`

### Release Candidates (5.0.0-rc.x)
- Production-ready candidate
- Bug fixes only
- Tag: `@rc`
- Install: `npm install @atmosphere/client@rc`

### Stable Releases (5.0.0)
- Production ready
- Tag: `@latest` (default)
- Install: `npm install @atmosphere/client`

## CI/CD Integration

### GitHub Actions (Recommended)
Create `.github/workflows/publish.yml`:

```yaml
name: Publish to NPM

on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          registry-url: 'https://registry.npmjs.org'
      
      - name: Install dependencies
        run: cd atmosphere.js && npm ci
      
      - name: Build
        run: cd atmosphere.js && npm run build
      
      - name: Test
        run: cd atmosphere.js && npm run test:ci
      
      - name: Publish
        run: cd atmosphere.js && npm publish --access public
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

## Troubleshooting

### "You must be logged in to publish"
```bash
npm login
npm whoami
```

### "You do not have permission to publish"
Request access to `@atmosphere` org or change package name in package.json

### "Version already exists"
```bash
npm version patch
git push --tags
```

### "Package not found after publishing"
Wait 1-2 minutes for npm registry to propagate

## Support

- Issues: https://github.com/Atmosphere/atmosphere/issues
- Docs: https://github.com/Atmosphere/atmosphere
- Chat: (Add community chat link)
