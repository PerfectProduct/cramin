# Frozen preferred sources

R8 full preferred sources at c331a820ddae7dea41f41a04375b784486d0f705 are preserved
byte-for-byte from the independently reviewed desugar source kit. Original source URL,
commit and exact SHA-256 remain in config/source-kit/r8.json. The tar includes upstream
LICENSE/LIBRARY-LICENSE/build scripts; notices remain unchanged.

Gitiles +archive returns new tar mtimes for the same commit, so a freshly generated
archive is not a byte-stable download. The release packager uses this frozen archive
when no explicit source cache is supplied and still verifies the original pinned SHA.
No checksum is removed, weakened or replaced with a new random download hash.
