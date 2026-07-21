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
  appear in any commit, ref, tag, or file reachable from the remote. The complete
  original history is retained only in a private, offline backup.
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
