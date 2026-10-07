# Open Check Lists

To-do lists with sections (a grocery list split by the shop's aisles), for Android, iOS, web and
desktop. A list can be kept in a Nextcloud share so that anyone with the link can edit it.

Kotlin Multiplatform with one Compose UI for every platform.

| Module       | What it is                                                                  |
|--------------|-----------------------------------------------------------------------------|
| `shared`     | List model, merge rules, local storage, Nextcloud sync. No UI.              |
| `ui`         | The Compose app (`OpenCheckListsApp`), plus the iOS entry point `MainViewController`. |
| `androidApp` | Android shell.                                                              |
| `desktopApp` | Desktop shell (Linux/macOS/Windows).                                        |
| `webApp`     | Browser shell (Kotlin/Wasm).                                                |
| `iosApp`     | Swift shell, as an XcodeGen spec.                                           |

## Build and run

```sh
./gradlew :shared:jvmTest                       # tests
./gradlew :androidApp:assembleDebug             # APK in androidApp/build/outputs/apk/debug
./gradlew :desktopApp:run                       # or :desktopApp:createDistributable
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # dev server; :webApp:wasmJsBrowserDistribution for a static build
```

The desktop app keeps its lists in `~/.local/share/open-check-lists`. Pass another directory as the first
argument (or set `OPENCHECKLISTS_DATA`) to run a second, independent "device". `OPENCHECKLISTS_OPEN=<list id>` opens
that list at launch.

**iOS** needs a Mac: `brew install xcodegen`, then `cd iosApp && xcodegen`, and open
`OpenCheckLists.xcodeproj`. The Xcode build calls Gradle to build the Kotlin framework. The iOS targets
are declared in Gradle but skipped on Linux.

## Settings

The ⋮ menu on the list overview opens **Settings**: theme (system default, light or dark) and
language (system default, English or Italiano). They are kept per device in `settings.json`, next
to the lists. Texts live in `ui/.../Strings.kt` (screens) and `shared/.../Messages.kt` (sync and
sign-in errors); a new language is one more object in each.

## App icon

The icon is drawn in `art/`: `app-icon.svg` (the rounded tile, for desktop and the web favicon),
`app-icon-square.svg` (iOS, which rounds the corners itself) and `android-foreground.svg` (the
Android adaptive icon's foreground, with a margin for launcher effects; its background is the colour
in `ic_launcher_background.xml`). After changing them, run `art/render-icons.sh` (needs
`rsvg-convert` and ImageMagick) to regenerate the PNGs in each app.

## Sharing a list

### Google Drive

Open the list, tap the share icon, and choose **Create a Google Drive link**. The app creates a
file in your Drive, named after the list (`Groceries.json`, numbered if that name exists), sets it to "anyone with the link can edit", and syncs the list into it. Send
the link to the others. They choose **Open a shared list**, paste it, and sign in with any Google
account. Google does not allow anonymous edits, so everyone who edits signs in.

An existing Drive file works too: it must be a plain file (not a Google Doc), empty or a list
from this app, and shared as "anyone with the link: Editor". Older links containing
`resourcekey=` must be pasted whole.

### Nextcloud

1. In Nextcloud, create a folder (for example "Groceries").
2. Share it by link and allow **upload and editing**. A share password is optional.
3. In the app, open the list, tap the share icon, and paste the link.
4. Send others the address the app then shows (share icon on the list). It names the list, like
   `https://cloud.example.com/s/<token>?file=Groceries.json`, so it opens that list directly. They
   use **Open a shared list** (share icon on the first screen). Given just the folder link, the app
   shows the lists in the folder to pick from.

One folder can hold any number of lists. Each list gets its own file, named after the list:
`Groceries.json`, or `Groceries (2).json` if that name is taken, as Nextcloud numbers duplicates.
The name is claimed with a create-only write, so two lists created at the same moment still get
two files. The file keeps its name if the list is renamed later, because others' links point to
it. Lists shared into a folder before this change stay in `open-check-list.json`.

Creating that file needs a link that may add files, and a server that allows public uploads
(Administration → Sharing). If yours does not, share a single empty file with editing allowed
instead; changing an existing file needs only edit permission.

Any file used this way must be empty or already a list from Open Check Lists; anything else is
refused and left untouched.

The app reaches the share anonymously over WebDAV: at `/public.php/dav/files/<token>` on
Nextcloud 29 and later, and at the older `/public.php/webdav` on older servers. The old path must
not be used on current servers; Nextcloud 34 answers there with a folder that is not the share.

## Setting up Google sign-in

Google Drive needs a Google Cloud project with OAuth clients. Until one is configured, builds
simply hide the Drive button.

1. At https://console.cloud.google.com create a project and enable the **Google Drive API**.
2. **OAuth consent screen**: External, app name "Open Check Lists", and add the scope
   `https://www.googleapis.com/auth/drive`. While the app is in *Testing*, only listed test users
   can sign in, and their sign-in lasts 7 days. *Publishing* it without Google's review works for
   up to 100 users, who see an "unverified app" warning. A public release needs Google's review
   of this restricted scope, which includes a paid security assessment.
3. **Create OAuth client IDs**:
   - *Desktop app*: gives a client id and secret.
   - *Android*: package `eu.studiodeanna.openchecklists`, plus the SHA-1 of the signing
     certificate. For debug builds on this machine that is
     `64:5C:B3:B5:06:18:B7:0E:AF:CA:8F:ED:C6:85:48:B2:4D:DA:15:16`; add another client for the
     release key later.
   - *iOS*: bundle id `eu.studiodeanna.openchecklists`.
   - *Web application*: add the web app's address (and `http://localhost:8080` for development)
     under *Authorized JavaScript origins*.
4. Put the desktop, iOS and web ids in `GoogleOAuthConfig`
   (`shared/src/commonMain/kotlin/eu/studiodeanna/openchecklists/google/GoogleAuth.kt`). Android
   needs no id in code; set `ANDROID_CLIENT_REGISTERED = true` there instead.

The desktop app also reads `OPENCHECKLISTS_GOOGLE_CLIENT_ID` and
`OPENCHECKLISTS_GOOGLE_CLIENT_SECRET` from the environment, to try a project without rebuilding.

How each platform signs in:

| Platform | Sign-in                                         | Where the sign-in is kept                    |
|----------|-------------------------------------------------|----------------------------------------------|
| Android  | Google Play services                            | Play services                                |
| Desktop  | Default browser, reply via `127.0.0.1` (PKCE)   | `google-session.json` in the data directory  |
| iOS      | System web-authentication sheet (PKCE)          | `google-session.json` in Documents           |
| Web      | Google Identity Services popup                  | Memory only; lasts about an hour             |

## How edits from several people combine

Each list is one JSON file. Every section and item records when it last changed and on which
device, and deletions are kept as markers. Syncing works like this:

- read the file;
- merge it with the local copy, where the most recent change to each item or section wins;
- write the result back only if the file has not changed since it was read;
- if it has, read and merge again.

On Nextcloud the server enforces the "not changed since" check (`If-Match`). Drive cannot, so
the app re-checks Drive's version number just before writing. A save that still lands in that
small gap can overwrite someone's copy of the file. Their app still has their changes, though,
and writes them back on its next sync.

Additions from different people never overwrite each other. Two people changing the *same*
item at the same moment is resolved by the later change.

Open lists sync shortly after each edit and every 15 seconds. All lists sync on launch, and on
Android also on resume. Delete markers older than 90 days are dropped.

## Known limits

- **Google Drive sign-in has not been tried against Google yet.** It needs the OAuth clients
  above. Drive sync is tested against a simulated Drive (`FakeGoogleDrive`), and the PKCE
  pieces against the RFC test vectors.
- **iOS** code (including its sign-in) has not been compiled, because that needs a Mac.
- The desktop and iOS sign-ins store the refresh token in a plain file in the app's data
  directory. Moving it to the OS keychain is a future improvement.
- **Web and Nextcloud CORS.** Google Drive works from the web app. For Nextcloud, browsers only let the web app reach a Nextcloud server that sends
  CORS headers for `/public.php/webdav` (allow `Authorization`, `If-Match`, `If-None-Match`,
  `Depth`, and expose `ETag`). Stock Nextcloud does not, so web sync needs either a reverse-proxy
  rule on that server or a small proxy. The native apps are unaffected.
- Nextcloud sync is verified against a real Nextcloud 34 folder share (two devices, edits
  merged). To rerun that: `OCL_NEXTCLOUD_TEST_LINK=<folder link> ./gradlew :shared:jvmTest --tests
  '*RealNextcloud*'` (it creates and deletes `open-check-list.json`). Single-file shares and
  servers older than 29 are covered only by the simulated server.
