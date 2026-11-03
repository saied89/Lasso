# Lasso — Design

Small Android app: double-tap the back of the phone (Quick Tap) or tap the accessibility button in any app, the screen dims, and every piece of visible text becomes selectable. Select words, copy or share. No root.

The earlier plan (open Recents and auto-tap Pixel Launcher's "Select") was dropped. It depended on the launcher's UI, broke on updates and languages, and only worked on the Overview thumbnail. Lasso reads the text itself instead.

## Constraints

- Stock, locked Pixel. No root, no ADB/Shizuku, no Developer options (banking apps must keep working).
- Everything goes through one **AccessibilityService** (`LassoService`). No `SYSTEM_ALERT_WINDOW`: the service draws with a `TYPE_ACCESSIBILITY_OVERLAY` window.
- Text comes only from on-device OCR of a screenshot (ML Kit, bundled, Latin script). The service can't read other apps' content (`canRetrieveWindowContent` false). No INTERNET permission: ML Kit's usage-stats permissions are removed in the manifest. Nothing is stored.
- Reading the accessibility node tree was removed in Nov 2026 to narrow what the service can do for the Play review. It gave exact text and non-Latin scripts; see git history before that change.
- minSdk 30, targetSdk 37.

## Flow

1. **Trigger.** Does nothing when the screen is off or locked.
   - **Accessibility button or gesture** (`flagRequestAccessibilityButton`): works on any phone, including Samsung. Tapping it again closes Lasso.
   - **Quick Tap** (Pixel's back double-tap), set to *Open app → Lasso*. It opens `TriggerActivity`: no UI (`Theme.NoDisplay`), its own task, not in Recents. It calls `trigger(150 ms)` and finishes in `onCreate`.
   - `TriggerActivity` is also the launcher icon. If the referrer is the home screen app (or the service is off), it opens `MainActivity` instead. Needs a `<queries>` entry for the HOME intent to see the home app.
   - The "Select text on this screen" button in the app calls `trigger()` too.
2. **Wait for scrolling to stop.** If a `TYPE_VIEW_SCROLLED` event arrived in the last 120 ms, wait (max 600 ms), so the boxes match what's drawn.
3. **Screenshot** with `takeScreenshot()` (`canTakeScreenshot`), before the overlay appears. If it fails, a toast says the app blocks screenshots. Apps with `FLAG_SECURE` usually come back black instead, which shows "No text found".
4. **Recognize text** on a worker thread (`Ocr`): one block per line, with per-character boxes from ML Kit symbols (or evenly split words). Lines below 0.65 confidence are dropped (icons read as text). Lines outside the app area are dropped: the screen minus the status bar, navigation bar and keyboard insets from `currentWindowMetrics`. Lines are sorted top to bottom, then left to right.
5. **Show the overlay** (`SelectionOverlay`):
   - The screenshot is drawn over the app area, so the screen is frozen; system panels stay live.
   - Dark scrim with rounded bright holes around each line of text.
   - Optionally pauses media by taking transient audio focus.

## Selection UI

- Drag across words to select. Teardrop handles at both ends adjust the range.
- Double-tap a block to select all of it.
- Toolbar: **Copy**, **Share**, **Translate** (Google Translate's tap-to-translate popup via `ACTION_PROCESS_TEXT`, else a chooser of text apps), **All**.
- Toolbar above the selection (below if there's no room): **Copy**, **Share**, **All**.
- Copied text: blocks on the same visual line are joined inline (web pages split sentences into many nodes). A space is added if the source has one or there's a visible gap. Different lines are joined with a newline.
- **Which app is in front** comes from `TYPE_WINDOW_STATE_CHANGED` events' package name (no window list without window content access). SystemUI, keyboards and Lasso's own windows other than `MainActivity` are ignored.
- **Translate** keeps Lasso open: the overlay is removed (kept in memory) so Translate's popup is visible, and re-added with the same selection when the original app is back in front. Going to the home screen while it's hidden, or 5 minutes passing, closes Lasso.
- Closing: Back (swallowed in `onKeyEvent`, so the app below doesn't go back too), the accessibility button, tapping the dim area with nothing selected, Copy, Share, rotation, or another app coming to the front.

## Files

| File | Role |
|---|---|
| `LassoService.kt` | Back key, accessibility button, trigger, screenshot, app area, overlay window, copy/share, audio focus. |
| `Ocr.kt` | ML Kit text recognition of the screenshot into `TextBlock`s. |
| `TextModel.kt` | `TextBlock`, `Word`, `ScreenText`: line boxes, hit testing, building the copied text. |
| `SelectionOverlay.kt` | Scrim, highlights, handles, toolbar, touch handling. |
| `TriggerActivity.kt` | Launcher entry and Quick Tap target: starts selection, or opens settings from the home screen. |
| `Prefs.kt` | Settings: pause media (on by default). |
| `MainActivity.kt` | Disclosure, enable button, settings switches, sample text to try. |
| `res/xml/lasso_service.xml` | Service config: Back key filtering, accessibility button, screenshots. No window content. |

## Install / user setup

- Android 13+ marks sideloaded APKs with **Restricted settings**. Go to App info → ⋮ → *Allow restricted settings* before enabling the service. Installing over ADB avoids this.
- If a banking app objects to the active service, turn it off in Accessibility settings. No uninstall needed.

## Play Store notes

- Needs the in-app disclosure (done in `MainActivity`), the Accessibility API declaration form, and a privacy policy.
- Not an accessibility tool (`isAccessibilityTool` false), so the declaration has to justify the use. Approval isn't guaranteed.

## Known limits

- OCR reads Latin script only (no Arabic/Persian), can misread small or stylized text, and adds ~0.5 s. The debug APK is 76 MB because ML Kit ships native code for 4 ABIs; an App Bundle ships one.
- Apps that block screenshots (`FLAG_SECURE`: banking apps, private tabs) show no text.

## Test checklist

Done on a Pixel 9 Pro emulator (API 36):

- [x] Settings app: overlay lines up, drag across blocks, copy.
- [x] Chrome: article text, dialog covering the page, copied text joins correctly.
- [x] Keyboard open: overlay stays, field text selectable.
- [x] Back, tap outside, double-tap block, Share.
- [x] Real Pixel 9 Pro XL: Quick Tap works; theorie24 (Cordova/WebView 156) text readable as whole blocks.
- [x] `TriggerActivity` launched over Settings starts selection; with the home screen as referrer it opens settings.

Still to do on a real Pixel:

- [ ] Quick Tap opens selection (the emulator has no Quick Tap; check SystemUI isn't reported as the home app).

- [ ] Media pause and resume.
- [ ] Landscape, split screen, Flutter apps, games.
- [ ] Lock screen (should do nothing), service survives reboot.
- [ ] Banking app still opens with the service enabled.
