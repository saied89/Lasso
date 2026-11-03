<p align="center"><img src="docs/icon.svg" width="160" alt="Lasso icon: a lasso loop around one line of text"></p>

<h1 align="center">Lasso</h1>

Select any text on your Android screen, in any app. Then copy, share or translate it.

Trigger Lasso and the current app dims. Every piece of visible text becomes selectable, including text in apps that don't normally let you select it: buttons, lists, web pages, images and games. Drag across words to select them, then pick **Copy**, **Share** or **Translate**.

## Opening Lasso

- **Accessibility button or gesture:** works on any phone. In the Settings app, open Accessibility → Lasso → Lasso shortcut, and pick the floating button or the two-finger swipe up. Tap it again to close Lasso.
- **Double-tap the back of the phone:**
  - Pixel with Quick Tap: System → Gestures → Quick Tap → Open app → Lasso.
  - Samsung: install Good Lock and its RegiStar module, then Back-Tap action → Double tap → Open app → Lasso.

Press Back to close Lasso.

## How it works

When you open Lasso, it takes a screenshot and recognizes the text in it with ML Kit's on-device recognizer. Lasso doesn't read other apps' content: it only sees what's in the screenshot.

Lasso is an accessibility service because that's what lets it take the screenshot, draw over every app, catch the Back key while it's open and show the accessibility button.

Translate opens Google Translate's popup on top of Lasso. When you close the popup, your selection is still there.

## Privacy

- Lasso takes a screenshot only when you open it, and doesn't keep it after you close Lasso.
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

- Text recognition reads Latin script only, so Arabic, Persian, Chinese and other scripts can't be selected. It can also misread small or stylized text.
- Apps that block screenshots, like banking apps and private browser tabs, show no text.

See [quickselect-plan.md](quickselect-plan.md) for the design notes.

## License

[Apache License 2.0](LICENSE)
