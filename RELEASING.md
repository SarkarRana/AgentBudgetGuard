# Releasing to Maven Central

One-time setup and the repeatable steps for publishing a new version of `agentbudget-core`,
`agentbudget-spring`, and `agentbudget-spring-boot-starter` to Maven Central via the Sonatype
Central Portal. `agentbudget-demo` is never published -- see its own `skipPublishing`
configuration in the notes below; `maven.deploy.skip` alone does not stop it.

## One-time setup (per machine)

1. **Central Portal account**, signed in via GitHub OAuth, with the `io.github.<your-username>`
   namespace verified at [central.sonatype.com](https://central.sonatype.com).
2. **GPG key pair**:
   ```bash
   gpg --gen-key
   gpg --list-secret-keys --keyid-format=long   # note the key ID after rsa4096/
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --keyserver pgp.mit.edu --send-keys <KEY_ID>
   ```
   `keys.openpgp.org` requires clicking the email verification link it sends before your identity
   shows up on that server.
3. **User token**: generate at
   [central.sonatype.com/usertoken](https://central.sonatype.com/usertoken). The
   username/password pair is shown once — save it immediately.
4. **`~/.m2/settings.xml`**:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>gpg-sign</id>
         <activation><activeByDefault>true</activeByDefault></activation>
         <properties>
           <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
         </properties>
       </profile>
     </profiles>
   </settings>
   ```
   Leave the GPG passphrase out of this file — `mvn` will prompt for it interactively during
   signing.

## Every release

1. Bump the version in the root `pom.xml` and every module's `<parent><version>` (drop
   `-SNAPSHOT` for the release, e.g. `0.1.0`). All modules inherit the parent version, so this is
   the only place the number needs to change.
2. From the repo root:
   ```bash
   export GPG_TTY=$(tty)
   mvn clean deploy -Prelease
   ```
   This compiles, tests, builds sources/javadoc jars, GPG-signs every artifact, bundles them, and
   uploads the bundle to the Central Portal for validation. You'll be prompted for the GPG
   passphrase.

   `GPG_TTY` matters: without it, `gpg` can fail with `signing failed: Inappropriate ioctl for
   device` because `gpg-agent`'s terminal-based pinentry can't find a controlling terminal when
   Maven forks the signing process. If it still fails with that error after exporting `GPG_TTY`,
   switch to a GUI pinentry instead: `brew install pinentry-mac`, add
   `pinentry-program $(which pinentry-mac)` to `~/.gnupg/gpg-agent.conf`, then
   `gpgconf --kill gpg-agent` and retry.
3. In the [Central Portal deployments page](https://central.sonatype.com/publishing/deployments),
   watch the bundle move `PENDING` → `VALIDATING` → `VALIDATED`. If validation fails, fix the
   issue, bump nothing (re-deploying the same version is fine until it's published), and re-run
   step 2.
4. Click **Publish** on the validated deployment. It moves to `PUBLISHING` → `PUBLISHED`.
   Artifacts typically appear in Central search within 15–30 minutes.
5. Verify the release is actually resolvable: build the README quickstart in a project pointed at
   a clean local repository (`mvn -Dmaven.repo.local=/tmp/clean-repo ...`) against the new
   version.
6. Bump the root `pom.xml` and modules back to the next `-SNAPSHOT` version, commit, and tag the
   release commit (the one with the released version number) as `vX.Y.Z`.

## Notes

- The `release` profile (source/javadoc/gpg/publish plugins) is deliberately not active by
  default, so plain `mvn install` / `mvn test` never needs a GPG passphrase.
- The Maven coordinate is permanent once published — double-check the group id, artifact ids, and
  version before step 2, not after.
- `central-publishing-maven-plugin` takes over the deploy phase itself and does not honor
  `maven.deploy.skip` the way the standard deploy plugin does. `agentbudget-demo`'s own pom sets
  the plugin's actual equivalent, `skipPublishing`, inside a matching `release` profile — without
  it, the demo's unsigned, sourceless jar gets bundled into the release and fails Central's
  validation (missing signature, missing sources, missing javadoc). If a future module needs the
  same exclusion, copy that pattern rather than relying on `maven.deploy.skip`.
