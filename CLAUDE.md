# Lasso

Android app (`dev.saeed.lasso`, minSdk 30, targetSdk 37). An accessibility service takes a screenshot, recognizes its text with ML Kit, and shows an overlay where the user selects text. Design notes: `quickselect-plan.md`. Play Store kit: `play/README.md`.

## Working here

- Build and check: `./gradlew lintDebug testDebugUnitTest assembleDebug` (the same as CI).
- With more than one device connected, set `ANDROID_SERIAL` for adb and Gradle device tasks.
- Release signing comes from the `LASSO_KEYSTORE`, `LASSO_KEYSTORE_PASSWORD` and `LASSO_KEY_ALIAS` environment variables (GitHub secrets in CI). Never commit a keystore or print its password.
- Machine-specific notes (devices, key location) go in `CLAUDE.local.md`, which isn't committed.

## Tests

- Changes to logic get unit tests in `app/src/test`. Prefer plain JUnit: keep decisions in small functions that take plain values, and leave the Android calls at the edges.
- Use Robolectric only when the code needs Android classes (`Rect`, views, resources).
- Run `./gradlew testDebugUnitTest` before committing. Don't commit with failing tests.
- One end-to-end test (`app/src/androidTest/.../EndToEndTest.kt`) runs the whole flow on a device: `./gradlew connectedDebugAndroidTest`. CI runs it on release tags and from the Actions tab. Keep it to this one test; ask before adding more instrumentation tests.
- Gradle uninstalls the app after device tests, which turns off Lasso and clears its setup. On a phone someone uses, add `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
- Translate's round trip isn't covered by any test. Check it by hand on a device with Google Translate.
