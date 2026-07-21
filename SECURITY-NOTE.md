# Edge Sentinel — Security Note (signing key rotation)

## Status: RESOLVED (2026-07-21)

An earlier development history hardcoded a release-keystore password in
`app/build.gradle.kts`. That was a real credential exposure. It has been
remediated:

- **Key rotated.** The old release key is retired and treated as permanently
  compromised. A new 4096-bit RSA release key was generated and is stored
  **outside the repository** with `0600` permissions. Its password is strong and
  is not recorded in this repository.
- **Secret purged from history.** The published repository was rebuilt from a
  fresh, sanitized root commit of the remediated tree. The old password does not
  appear in any commit, ref, tag, tree, or file reachable from any branch or tag
  of this repository, and no working-tree file contains it. This repository was
  created fresh from the sanitized single-commit history rather than rewritten
  in place, so no orphaned/dangling pre-rewrite objects carrying the old
  credential remain reachable by direct object SHA. (Note: force-pushing or
  squashing history *in place* on a Git host does not immediately remove
  unreachable objects — hosts such as GitHub can continue to serve dangling
  commits by direct SHA until their own garbage collection runs. This repository
  avoids that failure mode entirely by starting from a clean object store.) The
  complete original history is retained only in a private, offline backup and a
  separate private archival repository for recovery purposes.
- **Build fails safe.** `build.gradle.kts` loads signing credentials only from an
  untracked `keystore.properties` file or `EDGE_SENTINEL_*` environment
  variables. With no credentials present, release builds are left **unsigned**
  rather than embedding any key.
- **.gitignore** excludes `*.jks`, `*.keystore`, and `keystore.properties`.

## Operator follow-ups

- Devices with an APK signed by the old key must be uninstalled before installing
  a build signed with the new key.
- If Edge Sentinel is ever published on Google Play using the old upload key,
  request an upload-key reset (App Signing by Google Play).
- Provide new signing credentials only via `keystore.properties` / env vars.
  Never commit them. See `keystore.properties.template`.
