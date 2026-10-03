# Third-party distribution inventory (AUD-007)

The root Cramin licence is unchanged. This is an inventory and preparation record, not approval to publish.

`app/src/main/assets/legal/inventory.json` records 117 resolved coordinates across releaseRuntimeClasspath,
debugRuntimeClasspath and coreLibraryDesugaring, their configurations and artifact SHA-256. It is a
conservative distribution inventory: R8 may remove unused classes; debug-only entries are explicitly
labelled. Build tools and test dependencies are not represented as shipped application dependencies.
`legal/index.json` and the numbered text files preserve primary licence texts/attributions and are
readable offline in Settings → Licences. Binary META-INF duplicate exclusions do not remove these assets.

## Primary evidence

- NewPipeExtractor v0.26.5, commit f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef: GPL-3.0-or-later (source headers and LICENSE).
  https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5
- nanojson fork e9d656ddb49a412a5a0a5d5ef20ca7ef09549996: Apache-2.0 in Java source headers.
- Rhino 1.8.1, f2d1ffef9dafbd625ca1ea79b8893ecbaee07e61: MPL-2.0 and original NOTICE.
  https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release
- PdfBox-Android 2.0.27.0, 45da92629dad5b3c9887eceefecc89a1423f5457: Apache-2.0 and NOTICE;
  bundled resource copyright headers retained. Liberation Sans TTF name table explicitly identifies
  SIL OFL-1.1, Google 2010 and Red Hat 2012 copyright. This font is a PDF resource, not the UI font.
- desugar_jdk_libs_nio 2.1.5: GPL-2.0 with Classpath Exception; release-preparation source commit
  73170c345e6a762fc6a1f0301bb15218850023ef. Configuration artifact: BSD-3-Clause.
  https://github.com/google/desugar_jdk_libs/commit/73170c345e6a762fc6a1f0301bb15218850023ef
- AndroidX/Kotlin/OkHttp/Okio/Readability4J/jspecify/jsr305/annotations: Apache-2.0;
  AndroidX shaded protobuf and protobuf-javalite: BSD-3-Clause; jsoup/slf4j: MIT;
  Bouncy Castle: its MIT-style licence. Exact versions and original POM licence references are in inventory.

## Prepared source kit

Permanent location: `/home/dev/cramin-completion/2026-10-03/source-kit/`.
115 source JARs and 117 POMs have been downloaded from official Maven repositories/JitPack, with
URL/SHA-256 manifests. Full upstream source archives include NewPipe, nanojson, Rhino, PdfBox,
desugar and Liberation Fonts (the latter is an upstream source reference, not a claim of identical
font build). Final Cramin source archive is added from the exact final commit, excluding secrets and
local files. Build instructions accompany the kit; dependencies are unmodified upstream binaries.

Two desugar sources classifiers are not published (HTTP 404). Library release-preparation sources
are archived instead. The configuration source/build tree is archived from R8 commit c331a820ddae7dea41f41a04375b784486d0f705, whose nio JSON identifies 2.1.5; its exact published binary-to-source
reproduction has not been independently verified. The entire dependency graph has NOT been rebuilt
from source, and byte-identical reproducibility is NOT claimed.

## Publication gate / owner decision

With statically bundled GPL NewPipeExtractor, proposed distribution is the combined APK under
GPL-3.0-compatible terms, with complete corresponding source and build instructions available next
to every APK (GPL §6(d)), retaining the original permissive notices. MPL files remain available in
source form with their original notices. This proposal does not automatically relicense Cramin's
root sources. Owner must choose: (1) approve these combined-distribution terms and source delivery,
or (2) postpone distribution / separately authorise replacing NewPipe. A source link alone and the
old short licence list are insufficient. Before public distribution, verify completeness of the
corresponding source kit, resolve the desugar configuration provenance/build gap and attach the
kit to the exact binary version. No public publication was performed in this work.
