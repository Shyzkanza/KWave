# Publishing Secrets

KWave is published to Maven Central via the
[Sonatype Central Portal](https://central.sonatype.com/) using the
[vanniktech `maven-publish`](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin.
The `.github/workflows/publish.yml` workflow runs on every `X.Y.Z` tag and reads its credentials
from **GitHub Actions repository secrets**.

This file documents the five secrets the workflow expects, how to obtain each one, the one-time
namespace verification, and the end-to-end release runbook. It contains **no secret values** — only
the names and provisioning steps.

## Required GitHub Actions secrets

Configure these under **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Required | Purpose |
| --- | --- | --- |
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | Yes | Central Portal **User Token** username. |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | Yes | Central Portal **User Token** password. |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | Yes | ASCII-armored GPG **private** key used to sign artifacts. |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | Yes | Passphrase for the GPG private key. |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyId` | Optional | Short (8-char) GPG key id; only needed when the keyring holds multiple keys. |

The `ORG_GRADLE_PROJECT_` prefix lets each secret feed the matching Gradle project property without
any extra wiring in `build.gradle.kts`. The vanniktech plugin reads them automatically.

### 1. Central Portal user token — `mavenCentralUsername` / `mavenCentralPassword`

Maven Central authenticates with a generated **User Token**, not your account password.

1. Sign in at <https://central.sonatype.com/>.
2. Open **Account → Generate User Token** (top-right account menu → *View Account*).
3. Copy the generated **username** and **password** values.
4. Store them as `ORG_GRADLE_PROJECT_mavenCentralUsername` and
   `ORG_GRADLE_PROJECT_mavenCentralPassword` respectively.

Regenerating the token invalidates the previous pair — update both secrets together if you rotate it.

### 2. GPG signing key — `signingInMemoryKey` / `signingInMemoryKeyPassword` / `signingInMemoryKeyId`

Maven Central requires every published artifact to be GPG-signed.

Generate a key (skip if you already have one):

```bash
# Interactive key generation (choose RSA, 4096 bits, a real name + email, and a strong passphrase).
gpg --full-generate-key

# List keys to find the long key id (the LONG hex string after "sec rsa4096/").
gpg --list-secret-keys --keyid-format LONG
```

Export the **private** key in ASCII-armored form for `signingInMemoryKey`:

```bash
# Replace <KEY_ID> with the long key id from the previous command.
gpg --armor --export-secret-keys <KEY_ID>
```

Copy the entire block, including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and
`-----END PGP PRIVATE KEY BLOCK-----` lines, into `ORG_GRADLE_PROJECT_signingInMemoryKey`.
GitHub Actions secrets preserve the multi-line value as-is.

- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` — the passphrase you set during key generation.
- `ORG_GRADLE_PROJECT_signingInMemoryKeyId` — optional; set it to the **short** (last 8 chars) key
  id only if your keyring contains more than one secret key, so the plugin selects the right one.

Publish the **public** key to a keyserver so Maven Central can verify the signatures:

```bash
# Send the public key to a well-known keyserver (any one is sufficient; they sync).
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

## One-time namespace verification

Before the first publish you must verify ownership of the `red.rankorr` namespace
(the project's `GROUP`). It is the reverse-DNS of the `rankorr.red` domain, so it is verified by
**DNS**, proving you control that domain.

1. Sign in at <https://central.sonatype.com/>.
2. Go to **Namespaces → Add Namespace** and enter `red.rankorr`.
3. The Portal shows a **verification key** (a random token). Add it as a **DNS `TXT` record** on the
   `rankorr.red` domain (at the apex / root), with the token as the record value. Save it at your DNS
   provider and allow a few minutes for propagation.
4. Back in the Portal, click **Verify Namespace**. Once it shows as **verified**, you can remove the
   `TXT` record — verification is a one-time step and stays verified.

Deployments to an unverified namespace are rejected, so confirm verification before tagging a release.

## Release runbook

CI on `main` validates every push (detekt, API check, unit tests, Roborazzi). Releases are cut by
pushing a SemVer tag; the `publish.yml` workflow then uploads the deployment to the Central Portal.

1. **Bump the version.** In `gradle.properties`, change `VERSION_NAME` from `X.Y.Z-SNAPSHOT` to the
   release version `X.Y.Z` (no `v` prefix, no `-SNAPSHOT` suffix).
2. **Update the changelog.** Move the `[Unreleased]` entries under a new `[X.Y.Z]` heading in
   `CHANGELOG.md`.
3. **Commit on `main`.** Commit the version bump and changelog directly on `main`.
4. **Tag the release.** Create an annotated tag matching the version exactly:
   ```bash
   git tag X.Y.Z
   ```
5. **Push the commit and the tag.**
   ```bash
   git push origin main
   git push origin X.Y.Z
   ```
   Pushing the `X.Y.Z` tag triggers `publish.yml`, which builds all targets (Android/JVM/iOS) and
   runs `publishToMavenCentral` to **upload** the deployment to the Central Portal.
6. **Publish in the Portal.** Because `automaticRelease = false`, the upload is **not** released
   automatically. Open <https://central.sonatype.com/>, go to **Deployments**, confirm the
   validation passed, and click **Publish**. The artifacts then sync to Maven Central
   (availability can take a short while to propagate to search and consumers).
7. **Resume snapshot development.** After the release, bump `VERSION_NAME` back to the next
   `X.Y.Z-SNAPSHOT` on `main` and commit.

### Verification

After the Portal sync completes, confirm the artifact is live:

- Search: <https://central.sonatype.com/artifact/red.rankorr/kwave>
- The Maven Central badge in `README.md` updates to the new version automatically.
