# Lasso — Design

Small Android app: double-tap the back of the phone (Quick Tap) or double-press Volume Down in any app, the screen dims, and every piece of visible text becomes selectable. Select words, copy or share. No root.

The earlier plan (open Recents and auto-tap Pixel Launcher's "Select") was dropped. It depended on the launcher's UI, broke on updates and languages, and only worked on the Overview thumbnail. Lasso reads the text itself instead.

## Constraints

- Stock, locked Pixel. No root, no ADB/Shizuku, no Developer options (banking apps must keep working).
- Everything goes through one **AccessibilityService** (`LassoService`). No `SYSTEM_ALERT_WINDOW`: the service draws with a `TYPE_ACCESSIBILITY_OVERLAY` window.
- Text comes from two sources, merged: the accessibility node tree (exact text) and on-device OCR of a screenshot (ML Kit, bundled, Latin script). No INTERNET permission: ML Kit's usage-stats permissions are removed in the manifest. Nothing is stored.
- minSdk 30, targetSdk 37.

## Flow

1. **Trigger.** `onKeyEvent` sees two Volume Down presses within 350 ms.
   - The first press passes through (keys can't be held and replayed), so it lowers the volume one step. Optionally undone with `adjustSuggestedStreamVolume(ADJUST_RAISE)`.
   - The second press's down, up and repeats are swallowed.
   - Does nothing when the screen is off or locked.
   - Or **Quick Tap** (Pixel's back double-tap), set to *Open app → Lasso*. It opens `TriggerActivity`: no UI (`Theme.NoDisplay`), its own task, not in Recents. It calls `trigger(150 ms)` and finishes in `onCreate`.
   - `TriggerActivity` is also the launcher icon. If the referrer is the home screen app (or the service is off), it opens `MainActivity` instead. Needs a `<queries>` entry for the HOME intent to see the home app.
   - The "Select text on this screen" button in the app calls `trigger()` too.
2. **Wait for scrolling to stop.** If a `TYPE_VIEW_SCROLLED` event arrived in the last 120 ms, wait (max 600 ms), so the boxes match what's drawn.
3. **Screenshot** with `takeScreenshot()` (`canTakeScreenshot`), before the overlay appears. Fails on apps that block screenshots; then only accessibility text is used. Can be turned off in settings.
4. **Read the screen** on a worker thread (`TextExtractor`):
   - All `TYPE_APPLICATION` windows, topmost first. Text covered by a window above is dropped (dialogs over apps). SystemUI is skipped.
   - Depth-first walk of visible nodes, max 4000 per window.
   - Use `text`. Fall back to `contentDescription` only for leaf, non-clickable labels. Skip passwords, WebView roots (their text is the page title) and images.
   - Drop an outer node when an inner node shows the same text.
   - Ask for per-character boxes with `refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)`, max 5000 chars per node. If every box is identical (WebView 156+ reports the node's whole box for each character), the boxes are ignored. Without boxes, the whole node is one selectable unit. Icon-font names like `arrow_back` are dropped.
5. **Recognize text** in the screenshot (`Ocr`): one block per line, with per-character boxes from ML Kit symbols (or evenly split words). Lines below 0.65 confidence are dropped (icons read as text).
6. **Merge** (`TextExtractor.merge`): accessibility blocks with real character boxes win; OCR lines on top of them are dropped. Accessibility blocks without boxes are replaced by the OCR lines inside them. Remaining OCR lines (images, games) are added. OCR outside the app area (status bar, keyboard, volume panel) is ignored.
7. **Show the overlay** (`SelectionOverlay`):
   - The screenshot is drawn over the app area, so the screen is frozen; system panels stay live.
   - Dark scrim with rounded bright holes around each line of text.
   - Optionally pauses media by taking transient audio focus.
8. **Refresh.** Chrome builds its tree lazily, so the first read can be partial. Re-read at 300, 800 and 1500 ms and merge with the same OCR result; swap it in if it has more words and the user hasn't started selecting.

## Selection UI

- Drag across words to select. Teardrop handles at both ends adjust the range.
- Double-tap a block to select all of it.
- Toolbar: **Copy**, **Share**, **Translate** (Google Translate's tap-to-translate popup via `ACTION_PROCESS_TEXT`, else a chooser of text apps), **All**.
- Fallback when neither source gives word positions: tapping a multi-word block opens a card (`WordPicker`, a `TYPE_ACCESSIBILITY_OVERLAY` dialog) with Android's own text selection and menu, including other apps' text actions. Menu actions that start activities are routed through the service (needs `FLAG_ACTIVITY_NEW_TASK`).
- Toolbar above the selection (below if there's no room): **Copy**, **Share**, **All**.
- Copied text: blocks on the same visual line are joined inline (web pages split sentences into many nodes). A space is added if the source has one or there's a visible gap. Different lines are joined with a newline.
- **Translate** keeps Lasso open: the overlay is removed (kept in memory) so Translate's popup is visible, and re-added with the same selection when the original app is back on top (`TYPE_WINDOWS_CHANGED`). Going to the home screen while it's hidden, or 5 minutes passing, closes Lasso.
- Closing: Back, any volume key, tapping the dim area with nothing selected, Copy, Share, rotation, or another app coming to the front.

## Files

| File | Role |
|---|---|
| `LassoService.kt` | Key handling, trigger, overlay window, refresh, copy/share, audio focus. |
| `TextExtractor.kt` | Reads windows and nodes into `TextBlock`s; app area; merges with OCR. |
| `Ocr.kt` | ML Kit text recognition of the screenshot into `TextBlock`s. |
| `TextModel.kt` | `TextBlock`, `Word`, `ScreenText`: line boxes, hit testing, building the copied text. |
| `SelectionOverlay.kt` | Scrim, highlights, handles, toolbar, touch handling. |
| `WordPicker.kt` | Fallback dialog with native text selection for blocks without positions. |
| `TriggerActivity.kt` | Launcher entry and Quick Tap target: starts selection, or opens settings from the home screen. |
| `Prefs.kt` | Settings: volume trigger, undo volume change, pause media (all on by default). |
| `MainActivity.kt` | Disclosure, enable button, settings switches, sample text to try. |
| `res/xml/lasso_service.xml` | Service config: key filtering, interactive windows, window content. |

## Install / user setup

- Android 13+ marks sideloaded APKs with **Restricted settings**. Go to App info → ⋮ → *Allow restricted settings* before enabling the service. Installing over ADB avoids this.
- If a banking app objects to the active service, turn it off in Accessibility settings. No uninstall needed.

## Play Store notes

- Needs the in-app disclosure (done in `MainActivity`), the Accessibility API declaration form, and a privacy policy.
- Not an accessibility tool (`isAccessibilityTool` false), so the declaration has to justify the use. Approval isn't guaranteed.

## Known limits

- OCR reads Latin script only (no Arabic/Persian), can misread small or stylized text, and adds ~0.5 s. The debug APK is 76 MB because ML Kit ships native code for 4 ABIs; an App Bundle ships one.

- The volume panel shows for ~3 s on top of the overlay after the first press.
- Apps that mark views `accessibilityDataSensitive`, or draw text themselves (games, some Flutter/canvas apps), show little or no text. `FLAG_SECURE` alone doesn't block it.
- WebView 156+ (e.g. Cordova apps like theorie24) reports no character boxes; OCR provides word positions there. Tried and failed to get real boxes from WebView 156: all event types, spoken feedback + extra flags, accessibility focus before the request.
- Hidden web labels can still light up under icons in some pages.

## Test checklist

Done on a Pixel 9 Pro emulator (API 36):

- [x] Settings app: overlay lines up, drag across blocks, copy.
- [x] Chrome: article text, dialog covering the page, copied text joins correctly.
- [x] Keyboard open: overlay stays, field text selectable.
- [x] Volume unchanged after the double-press, also when holding the second press.
- [x] Back, tap outside, double-tap block, Share.
- [x] Real Pixel 9 Pro XL: Quick Tap works; theorie24 (Cordova/WebView 156) text readable as whole blocks.
- [x] `TriggerActivity` launched over Settings starts selection; with the home screen as referrer it opens settings.

Still to do on a real Pixel:

- [ ] Quick Tap opens selection (the emulator has no Quick Tap; check SystemUI isn't reported as the home app).

- [ ] Media pause and resume.
- [ ] Landscape, split screen, Flutter apps, games.
- [ ] Lock screen (should do nothing), service survives reboot.
- [ ] Banking app still opens with the service enabled.
- [ ] Single and long volume presses still behave normally.
