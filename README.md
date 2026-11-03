# Lasso

Select any text on your Android screen, in any app. Then copy, share or translate it.

Trigger Lasso and the current app dims. Every piece of visible text becomes selectable, including text in apps that don't normally let you select it: buttons, lists, web views, images and games. Drag across words to select them, then pick **Copy**, **Share** or **Translate**.

## Opening Lasso

- **Accessibility button or gesture:** works on any phone. In the Settings app, open Accessibility → Lasso → Lasso shortcut, and pick the floating button or the two-finger swipe up. Tap it again to close Lasso.
- **Double-tap the back of the phone:**
  - Pixel with Quick Tap: System → Gestures → Quick Tap → Open app → Lasso.
  - Samsung: install Good Lock and its RegiStar module, then Back-Tap action → Double tap → Open app → Lasso.

Press Back to close Lasso.

## How it works

Lasso is an accessibility service. When you open it, it reads the text from two sources and combines them:

1. **The app's accessibility text:** exact text with the position of every character, where the app provides it.
2. **Text recognition on a screenshot:** for web views, images and games that don't report where their text is. This uses ML Kit's on-device recognizer, which reads Latin script only.

Translate opens Google Translate's popup on top of Lasso. When you close the popup, your selection is still there.

## Privacy

- Lasso reads the screen only when you open it.
- Text recognition runs on the phone.
- Nothing is saved, and the app has no internet permission.

## Install

Build it with Android Studio, or from the command line:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 11 (API 30) or newer. Open Lasso and follow the steps to turn on its accessibility service.

On Android 13+, apps installed from a file can't turn on accessibility services at first. Open App info → ⋮ → Allow restricted settings, then try again. Installing with `adb` avoids this.

If a banking app complains about an active accessibility service, turn Lasso off in Accessibility settings while you use it.

## Known limits

- Text recognition reads Latin script only, and can misread small or stylized text.
- Apps that block screenshots, or hide their text from accessibility services, show little or no text.
- Recent WebView versions don't report character positions, so web text relies on text recognition.

See [quickselect-plan.md](quickselect-plan.md) for the design notes.

## License

[Apache License 2.0](LICENSE)
