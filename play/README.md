# Publishing Lasso on Google Play

Everything needed to create the Play listing. Steps marked **(you)** need your Google account.

## What's in this folder

| File | Use |
|---|---|
| `Lasso-1.1.0.aab` | The App Bundle to upload (version 1.1.0, code 2), signed with your key in `~/.config/lasso/`. Not in git. |
| `lasso-play-declaration.mp4` | Video for the Accessibility API declaration. Not in git. |
| `graphics/icon-512.png` | App icon, 512×512 |
| `graphics/feature-graphic.png` | Feature graphic, 1024×500 |
| `graphics/screenshot-1..3.png` | Phone screenshots, 1080×1920 |
| `listing.md` | App name, descriptions, category, contact details |
| `../PRIVACY.md` | Privacy policy (needs to be pushed to GitHub before you submit) |

To rebuild the bundle:

```sh
set -a; . ~/.config/lasso/lasso-release.properties; set +a
LASSO_KEYSTORE=~/.config/lasso/lasso-release.jks ./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

## 1. Developer account (you)

- Sign up at play.google.com/console: a one-time $25 fee plus identity verification. This can take a few days.
- **Personal accounts created after November 2023 must run a closed test first:** at least 12 testers opted in for 14 days in a row before you can apply for production. Check the current numbers in Play Console when you sign up. Organization accounts skip this but need a D-U-N-S number.

## 2. Create the app (you)

Play Console → Create app:

- App name: `Lasso: Select Any Text`
- Default language: English (United States)
- App or game: App. Free or paid: Free.

## 3. App signing: decide before the first upload

On the first upload, Play asks how to sign the app:

- **Let Google create the signing key (default).** Your key becomes the upload key only. Play installs and the GitHub Releases APK then have different signatures, so users can't switch between the two without uninstalling.
- **Use your existing key.** Upload it with Google's PEPK tool when asked. Play and GitHub builds then share one signature and update over each other. Recommended if you keep publishing APKs on GitHub.

Either way, keep `~/.config/lasso/` backed up. Losing the upload key is recoverable through Google support; losing the key itself is not, if Google doesn't hold it.

## 4. Store listing

Grow users → Store presence → Main store listing: paste the text from `listing.md`, then upload the icon, feature graphic and screenshots from `graphics/`.

Store settings: category **Tools**, plus the contact email and website from `listing.md`.

## 5. App content (Policy → App content)

Answer each section like this:

- **Privacy policy:** `https://github.com/saied89/Lasso/blob/main/PRIVACY.md`
- **Ads:** No, the app doesn't contain ads.
- **App access:** All or some functionality is restricted. Add these instructions:

  > No account is needed. Lasso works only after its accessibility service is on:
  > 1. Open Lasso and tap "Agree and open settings".
  > 2. Tap "Lasso text selection", turn on its switch, and tap Allow.
  > 3. Open any app with text (e.g. a web page in Chrome) and tap the floating accessibility button. The screen dims.
  > 4. Drag across words to select them, then tap Copy, Share or Translate. Press Back to close.

- **Content rating:** fill in the questionnaire with category "Utility, productivity, communication or other". Answer No to everything (violence, sexuality, user interaction, location sharing, purchases, and so on). This should give the lowest rating (Everyone / PEGI 3).
- **Target audience:** 18 and over (or 13+). Don't include under-13 ages, which brings in the Families policy.
- **News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.
- **Advertising ID:** No. The release build doesn't declare the AD_ID permission.
- **Data safety:**
  - Does your app collect or share any of the required user data types? **No.** Lasso has no internet permission, so nothing leaves the phone.
  - This results in "No data collected" and "No data shared". Play may ask about encryption in transit and deletion requests; those don't apply when no data is collected.

## 6. Accessibility API declaration

Policy → App content → Sensitive permissions and APIs (it appears after the bundle is uploaded):

- **Is the app an accessibility tool?** No.
- **Core functionality** (draft):

  > Lasso lets users select, copy, share and translate text that apps don't let them select. When the user opens Lasso (with the accessibility button, a gesture, or Pixel's Quick Tap), the accessibility service takes one screenshot with takeScreenshot() and recognizes the text in it on the device with ML Kit. It shows the selection screen over the current app as an accessibility overlay, provides the accessibility button, and filters the Back key so Back closes Lasso without affecting the app below. The service can't read window content (canRetrieveWindowContent is false), performs no actions on other apps, and the app has no internet permission. Nothing is stored or sent.

- **Video:** upload `lasso-play-declaration.mp4` to YouTube as **unlisted** and paste the link. It shows the in-app disclosure in full, the consent tap, turning on the service, and Lasso in use.

Submit this form again whenever the way the service is used changes.

## 7. Release

1. Testing → Closed testing → create a track, add your testers' emails (or a Google Group), upload `Lasso-1.1.0.aab`, and roll out.
2. After 14 days with 12+ testers, apply for production access in the Dashboard.
3. Production → Create release → upload the same or a newer bundle. Each upload needs a higher `versionCode` in `app/build.gradle.kts`.

Review usually takes a few days. Apps using the accessibility service often get questions or a rejection on the first try. Reply with the declaration text above, and point out that the service has no window content access.
