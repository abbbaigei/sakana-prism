# Third-party notices

## SPW-Listen-Stats

This project's host UI structural discovery was adapted from:

- SPW-Listen-Stats by Jethuit
- https://github.com/Jethuit/SPW-Listen-Stats
- MIT License

The discovered code is used to read host class files, locate Compose call sites,
and install public Workshop hooks. The Salt Player audio kernel is not inspected
or modified.

## SPW-Lyrics

SPW-Listen-Stats attributes its structural discovery approach in part to
SPW-Lyrics (MIT, GaBoron). This project retains that attribution.

## BASS

BASS and its add-on DLLs are distributed with Salt Player for Windows. This
mod loads the installed native library through JNA and does not redistribute
the binaries.

## Compose Multiplatform / Kotlin

Compiled with Kotlin 2.4.20 and Compose Multiplatform 1.10.0. The host runtime
provides the Compose runtime used by the installed plugin.
