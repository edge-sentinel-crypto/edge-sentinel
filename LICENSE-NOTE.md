# Edge Sentinel — License & Provenance Note

## Summary

Edge Sentinel is proprietary, source-available software of BP22 Intel.
Copyright © 2024–2026 BP22 Intel. All rights reserved. See `LICENSE`.

The project is an independent, clean-room implementation. Its detection
*approach* is informed by public cellular-security research and prior art
(including SRLabs' SnoopSnitch and peer-reviewed academic work), but it does not
incorporate SnoopSnitch/SRLabs source code.

## Provenance review (2026-07-21)

A file-level comparison against the preserved upstream SnoopSnitch source
(`../upstream-snoopsnitch/`) supports the clean-room determination:

- **Native DIAG layer** (`app/src/main/cpp/diag-helper.c`): The upstream file of
  the same name is a standalone `main()` binary that daemonizes under `su` and
  moves bytes over stdin/stdout pipes using pthread data-pump threads and length
  framing. Edge Sentinel's file is a JNI library
  (`Java_com_bp22intel_edgesentinel_diag_DiagBridge_native*`) exposing discrete
  check/open/close/read/write/isOpen methods — no `main()`, no pthreads, no
  pipe/socket plumbing. The only commonality is non-copyrightable kernel-ABI
  fact (the `DIAG_IOCTL_SWITCH_LOGGING` value, memory-device mode, the packed
  mode-parameter struct, and the "try int then struct pointer" compatibility
  behavior), which is dictated by the public Qualcomm DIAG ICD (80-V1294-1) and
  the MSM kernel and is necessarily similar in any independent implementation.
- **Threat scoring** (`detection/scoring/ThreatScorer.kt`): An independent
  six-category weighted model. SnoopSnitch's `ImsiCatcher` coefficient set
  (`a1, a2, a4, a5, k1, k2, c1–c5, t1, t3, t4, r1, r2, f1`) does **not** appear
  anywhere in this codebase.
- No `de.srlabs` package code or copied Java was found in the tree; references to
  SnoopSnitch/SRLabs are limited to documentation/attribution.

On that basis the tree has been normalized consistently to the proprietary,
source-available license: `LICENSE`, `AndroidManifest.xml`, and all source
headers use the BP22 Intel proprietary notice, and stale GPLv3 headers/notes
left over from early development have been removed. Accurate acknowledgment of
inspiration and research is retained in `README.md` and the in-app About screen.

## Third-party dependency compliance (routine)

The app bundles standard open-source libraries (AndroidX/Jetpack, Dagger Hilt,
Room, osmdroid, JUnit). Those remain under their own licenses; attribution is in
`THIRD_PARTY_NOTICES.md`. Maintain that file and an SBOM as dependencies change.
This is routine dependency hygiene, not a distribution blocker.
