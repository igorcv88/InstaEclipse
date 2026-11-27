# Story Plus experiments — diagnostics and signed builds

## Scope
The viewer-list probe and tray probe are **off by default**. They do not unlock Instagram Plus server-side entitlements and do not claim to identify individual story replays. Anonymous preview is **not enabled**: the data source and seen-report lifecycle must be validated first.

- Story Viewer Diagnostics: observes the list before optional search/sort hooks, noting pseudonymized identities, relative order and timestamp-shaped primitive fields.
- Story Preview Diagnostics: inspects already inflated tray/adapter/holder class names to identify where preview media might be available without opening a story. No preview UI, media request or seen suppression has been added.
- The viewer IDs in logs are session-specific salted hashes; usernames, stories, URLs, media IDs and tokens are not logged.
- Candidate timestamp fields might represent unrelated metadata. A row moving upwards, by itself, does **not** establish that it was replayed.

## Controlled viewer test (two accounts)
1. After installing a build containing this branch, enable **Story Viewer Diagnostics (experimental)** in the module's Stories settings. Leave it off at other times.
2. Restart Instagram and publish a new story from account A.
3. Use account B to watch it once. Open account A's viewer sheet and keep it displayed for at least three seconds.
4. Close the viewer sheet, have account B watch it **again**, then reopen the sheet. Compare `(IE|ViewerDiag)` snapshots, focusing on the same pseudonym's timestamp candidates and position.
5. Repeat without B watching again as a control, and repeat with a separate new spectator to test whether updates are only ranking changes.
6. Export the in-app InstaEclipse log or use `adb logcat -d -s InstaEclipse:I`. Share only the `(IE|ViewerDiag)` entries. Turn the diagnostic toggle off after collecting data.

The probe limits records to 150 and emits at most 35 changed items per snapshot, with a 2.5-second throttle. Results cannot prove that the server tracks individual replays unless an independently validated event or field corroborates them.

## Pre-viewer tray model test
1. Enable **Story Preview Diagnostics (experimental)**, then restart Instagram.
2. Visit Home with unseen stories, scroll the horizontal story tray and switch tabs back to Home. Do **not** open the stories for this test.
3. Collect only `(IE|StoryPreviewDiag)` lines: list, adapter, row and holder class names. No account or story content is logged.
4. Turn the diagnostic off. These classes are starting points for finding the preloaded media models; they do not confirm a safe preview path.

## GitHub Actions
There are exactly two workflow files:
- `.github/workflows/main.yml`: CI on main commits and every PR update (unit tests, lint, compile). It **does not upload debug or unsigned APKs**.
- `.github/workflows/release.yml`: automatically generates a **signed PR APK** after successful CI, but **only** for PRs from the owner's own repository, triggered by `igorcv88`. PRs from other forks get no signing secrets or signed artifact. It also supports manual **Build signed InstaEclipse APK** from an already-merged main commit. Choose `artifact` (download expires after one day) or `release` (the same signed APK is uploaded as a persistent GitHub Release asset).

Signing requires four repository secrets with the **same names and values as OneUILiquidGlass**:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
GitHub secrets are scoped to repositories; check that these four are present in this fork before triggering. The workflow fails instead of uploading an unsigned APK if a secret is absent.

Optimizations: Java 17, `gradle/actions/setup-gradle` cache (distribution, dependencies, build-task outputs), `org.gradle.caching=true`, parallel workers with bounded concurrency, minimal SDK installation, and one Gradle invocation for test/lint/build. No extra Java Gradle cache and no NDK installation.

**Important:** The signed APK may not install as an update to an upstream APK signed with a different certificate. The signing secrets guarantee consistent signing across *our own* artifacts and releases, not compatibility with unrelated signing identities.

The PR should stay unmerged until CI is running and the diagnostic build is tested on a real Instagram version.

Para os checks locais e os casos instrumentados da biblioteca do Direct, consulte [Biblioteca de mídias](MEDIA_LIBRARY.md#build-e-atribuição).
