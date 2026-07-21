# Security Policy

## Reporting a vulnerability

If you discover a security vulnerability in Edge Sentinel, please report it
privately. Do **not** open a public issue for security problems.

- Use GitHub's private vulnerability reporting ("Report a vulnerability" under
  the repository Security tab), or
- Contact the maintainers at the address published on the repository owner's
  profile.

Please include: affected version/build, device and Android version, steps to
reproduce, and impact. We aim to acknowledge reports promptly and will
coordinate disclosure.

## Signing-key rotation notice

A previous release signing key was exposed and has been **retired and treated as
compromised**. It must never be used again. Current builds are signed with a new
key held out-of-tree, or are left unsigned when no signing material is supplied.
Devices with an app installed under the old key must uninstall before installing
a build signed with the new key. If this app is ever distributed via Google Play,
an upload-key reset (App Signing by Google Play) should be requested.

## Scope and expectations

Edge Sentinel is experimental defensive research software. It provides
heuristic indicators, not guarantees. Detection can produce false positives and
false negatives. Do not rely on it as sole protection in high-risk situations.

## Handling of secrets

- No signing keys, keystores, or credentials are committed to this repository.
  `*.jks`, `*.keystore`, and `keystore.properties` are gitignored.
- The app performs no telemetry and uses no Edge Sentinel backend. Any tokens
  (e.g. an OpenCelliD API token) are user-supplied and stored on-device only.
