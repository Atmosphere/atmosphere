# SDKMAN Vendor Onboarding — Atmosphere CLI

PR: https://github.com/sdkman/sdkman-db-migrations/pull/764
Maintainer feedback: 3 onboarding steps required before the PR can be merged.

---

## Step 1 — Well-formed SDK archive ✅

The release workflow (`release-4x.yml`) now builds an SDKMAN-compatible ZIP
automatically and attaches it to the GitHub Release.

**Archive layout** (per [SDKMAN spec](https://github.com/sdkman/sdkman-cli/wiki/Well-formed-SDK-archives)):

```
atmosphere-${VERSION}.zip
└── atmosphere-${VERSION}/
    └── bin/
        ├── atmosphere       # POSIX shell script (executable)
        └── atmosphere.bat   # Windows launcher
```

**Download URL format** (after each release):
```
https://github.com/Atmosphere/atmosphere/releases/download/atmosphere-${VERSION}/atmosphere-${VERSION}.zip
```

SHA-256 checksum is attached as `atmosphere-${VERSION}.zip.sha256`.

---

## Step 2 — GPG key & API credentials (ChefFamille's action)

### 2a. Export your armoured public GPG key

```bash
gpg --armor --export jfarcand@apache.org > /tmp/atmosphere-sdkman-key.asc
cat /tmp/atmosphere-sdkman-key.asc
```

### 2b. Email it to SDKMAN

**To:** info@sdkman.io
**Subject:** Atmosphere CLI — vendor onboarding (PR #764)

**Body template:**

```
Hi SDKMAN team,

Following the onboarding instructions on PR #764, here's our armoured
public GPG key for the Atmosphere CLI vendor account.

Candidate: atmosphere
PR:        https://github.com/sdkman/sdkman-db-migrations/pull/764
Maintainer: Jean-François Arcand (jfarcand@apache.org)
GitHub:    https://github.com/jfarcand
Project:   https://github.com/Atmosphere/atmosphere

Please reply with the encrypted vendor API credentials.

Thanks,
Jean-François Arcand
Atmosphere Framework maintainer

-----BEGIN PGP PUBLIC KEY BLOCK-----
<paste /tmp/atmosphere-sdkman-key.asc contents here>
-----END PGP PUBLIC KEY BLOCK-----
```

### 2c. Decrypt the response

SDKMAN will reply with a PGP-encrypted message containing `CONSUMER_KEY` and
`CONSUMER_TOKEN`. Decrypt with:

```bash
gpg --decrypt sdkman-response.asc
```

Store the credentials somewhere secure (1Password, `.envrc`, etc.) — do NOT commit them.

---

## Step 3 — First release via vendor API

Once you have `CONSUMER_KEY` and `CONSUMER_TOKEN`, run the helper script:

```bash
export SDKMAN_CONSUMER_KEY="<key-from-sdkman>"
export SDKMAN_CONSUMER_TOKEN="<token-from-sdkman>"
./cli/sdkman/publish.sh 4.0.36
```

The script will:
1. POST the new version to `https://vendors.sdkman.io/release`
2. Set it as the default version (PUT `/default`)
3. Broadcast the announcement (POST `/announce/struct`)

After a successful first release, the SDKMAN maintainers will merge PR #764,
and users can install via:

```bash
sdk install atmosphere
```

---

## Releasing subsequent versions

Every future release cycle:

1. `gh workflow run release-4x.yml -f release_version=<v> -f next_dev_version=<v+1>-SNAPSHOT`
2. Wait for green — GitHub Release includes `atmosphere-<v>.zip`
3. `./cli/sdkman/publish.sh <v>` to push to the SDKMAN vendor API

This could eventually be automated as a final phase in `release-4x.yml`, but
requires storing `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN` as GitHub
Actions secrets. Ask ChefFamille before wiring that up.
