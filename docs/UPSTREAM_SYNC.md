# Merge upstream DiPlay

TiPlay (https://github.com/uJZk/DiPlay) is a fork of DiPlay (https://github.com/shihabal3amri/DiPlay).
Use this procedure to merge new DiPlay commits into TiPlay.
The merge goes one way only. TiPlay sends no pull requests or patches to DiPlay.

## Rules that keep merges small

- Put each new TiPlay feature in new files.
- Give an upstream file only small hook edits: one call, one gate or one parameter, with a short comment that says why.
- Do not rename, move, reformat or delete upstream files, classes, resources or identifiers.
- Merge with a merge commit. Do not rebase `main`, squash an upstream merge or cherry-pick upstream commits.
  A merge commit records the common base, so the next merge brings only the new upstream commits.

## 1. Add the upstream remote

Do this once in each clone:

```sh
git remote add upstream https://github.com/shihabal3amri/DiPlay.git
git remote set-url --push upstream DISABLED
git config remote.upstream.tagOpt --no-tags
```

The second command makes an accidental push to DiPlay fail.
The third command keeps DiPlay's release tags (`v0.2.13` and later) apart from TiPlay's tags.

## 2. Fetch and look at the changes

```sh
git fetch origin
git fetch upstream
git log --oneline origin/main..upstream/main
git diff --stat origin/main...upstream/main
```

The three-dot `diff` shows only what DiPlay changed since the last merge.
Conflicts can occur only in files that both sides changed since the common base. To list them:

```bash
base=$(git merge-base origin/main upstream/main)
comm -12 <(git diff --name-only "$base" origin/main | sort) \
         <(git diff --name-only "$base" upstream/main | sort)
```

To see TiPlay's edits of one upstream file, run `git diff "$base" origin/main -- <file>`.
Read DiPlay's new release notes first: `git show upstream/main:CHANGELOG.md | head -40`.

## 3. Merge

Merge on a branch. Move `main` only after all checks pass.

```sh
git config merge.conflictStyle zdiff3
git config rerere.enabled true
git switch -c upstream-sync origin/main
git merge --no-ff upstream/main -m "Merge upstream DiPlay <version or short commit>"
```

`zdiff3` shows the common base in each conflict, so you can see what each side changed.
`rerere` records each resolution and applies it again if the same conflict comes back.
Resolve the conflicts (section 4), then run `git add` on the files and `git commit` to complete the merge commit.
To start again, run `git merge --abort`.

Make the rename pass (section 5) and the other fixes as separate commits on the same branch.
When the checks pass (sections 8 and 9):

```sh
git switch main
git merge --ff-only upstream-sync
git push origin main
```

## 4. Files that conflict most often

The general rule: keep upstream's change, then apply TiPlay's hook again on top of it.
Do not drop an upstream change to keep TiPlay's old text.
If the hook no longer fits, change the TiPlay file that the hook calls, not upstream's code.

### Kotlin

- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`:
  TiPlay adds `SettingsSection.TESLA_BROWSER` and its category, `headUnitOnlySections`, `showsSection()`, the Tesla
  browser card, the car Bluetooth sound control, phone-mode gates and TiPlay report names.
  Keep upstream's new sections and controls, and keep TiPlay's entries next to them.
  The start-with-car and USB controls of the `SettingsSection.AUTOMATIC_CONNECTION` card are inside
  `if (!AirPlayPersistence.isPhoneBrowserMode(this))`.
  TiPlay indented that block, so each upstream edit in it conflicts. Take upstream's block and put it inside the `if`
  again. `git diff -w` ignores the change of indentation.
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`:
  TiPlay changes the accessory name, serial prefix and label to TiPlay, and adds the `headUnitIntegrations()` and
  `clusterMapEnabled()` gates, `browserLinkHost` and `phoneBrowserAirPlayConfig()`.
  `phoneBrowserAirPlayConfig()` repeats the `AirPlayConfig(...)` call of `createAirPlayConfig()`.
  When upstream adds a field there, git shows no conflict. Add the field to `phoneBrowserAirPlayConfig()` too.
- `common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt`:
  TiPlay takes the notification title from `R.string.app_name`, leaves out the microphone service type for car
  Bluetooth sound, and calls `PhoneBrowserLocks`, `TeslaBrowserLink` and `HotspotExtraAddressSettings` in
  `onStartCommand`, `onDestroy` and `onTaskRemoved`. Keep these calls after upstream's changes.
- `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`:
  TiPlay adds the `run_mode` and `car_bluetooth_audio` keys with their load and save functions, and sets
  `DEFAULT_MANUFACTURER` and `DEFAULT_MODEL` to TiPlay. Keep the keys of both sides. Each key string must stay unique.
- `common/src/main/java/com/shilapi/xcertplay/BootReceiver.kt`, `CarPlayCallKeys.kt` and `WheelKeyService.kt`
  (same folder): one phone-mode gate each.
- `shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayConfig.kt`, `AirPlayInfoPlist.kt` and `AirPlaySession.kt`:
  car Bluetooth sound. `/info` leaves out `audioFormats`, and the session declines audio streams when `receivesAudio`
  is false. If upstream adds an audio stream type or changes the `/info` audio entries, apply the `receivesAudio` gate
  to it as well.
- `shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt`: `describeConfig()` and `configDiagnostic()`
  describe each video configuration once.
- `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`:
  every `BydNavigationOutputs` call, the car's home screen and the Wi-Fi scan pause are inside
  `if (config.headUnitIntegrations)`. Gate new upstream calls of this kind in the same way.
  `CarPlayRuntimeConfig.kt` in the same folder adds `CarPlayRunMode` and `headUnitIntegrations`.
- Files with TiPlay in a user-visible text or an application ID, for example
  `shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt` (the `TiPlay-` hotspot name),
  `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt` (Downloads/TiPlay) and
  `shared/src/main/java/com/shilapi/xcertplay/hud/BydStarterBridge.kt` (`com.ujzk.tiplay.hudtest`).
  Take upstream's change and keep the TiPlay text.

### Build files and manifests

- `common/build.gradle.kts`: the `BundleBrowserPage` task at the end copies the browser page into the app's assets.
  Keep it after upstream's changes.
- `mobile/build.gradle.kts`: keep `applicationId = "com.ujzk.tiplay"`.
  While TiPlay has no release of its own, it uses DiPlay's version numbers: take upstream's `versionCode` and
  `versionName`.
- `common/src/main/AndroidManifest.xml`: TiPlay adds the `WAKE_LOCK` and `ACCESS_LOCAL_NETWORK` permissions.
  Keep the permissions of both sides.
- `mobile/src/debug/AndroidManifest.xml`, `samples/home/src/main/AndroidManifest.xml` and
  `samples/maphost/src/main/AndroidManifest.xml`: keep the TiPlay labels.
- `.github/workflows/android.yml`: keep the Node 22 step that runs the browser page tests.

### String resources

- `strings.xml`, `hotspot_join.xml` and `strings_default_connection.xml` in `common/src/main/res/values/` and the
  `values-xx` folders, `strings_settings_layout.xml` in the `values-xx` folders, and
  `mobile/src/main/res/values/strings.xml`: TiPlay changed only the text, from DiPlay to TiPlay.
  Take upstream's text, then write TiPlay where it says DiPlay. Do not change the `name` attribute.
  The About credit (`receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay`) keeps TiPlay's text, which credits DiPlay.

### Documents and site

- `README.md` and `README.zh-CN.md`: TiPlay rewrote them. Keep TiPlay's version.
  Copy upstream's new facts into the head-unit mode section, and update the DiPlay version that TiPlay starts from.
- `CHANGELOG.md`: "TiPlay — unreleased" is first, then DiPlay's history.
  Put upstream's new DiPlay entries below the TiPlay section, unchanged: they keep the DiPlay name and the bare upstream
  issue numbers. Add a line to the TiPlay section that names the merged DiPlay version.
- `AGENTS.md`: take upstream's rule changes. Keep the TiPlay name, the page-test note, the Connection examples and the
  Naming and Upstream sections.
- `docs/RELEASE-NOTES-NEXT.md`: take upstream's entries, and link their issue numbers to DiPlay (section 5).
  When DiPlay releases, it renames this file to `RELEASE-NOTES-0.2.<n>.md`. Keep the released file as upstream wrote it,
  and keep TiPlay's sections in `docs/RELEASE-NOTES-NEXT.md`.
- Guides in `docs/`, such as `docs/BUILD.md`, `docs/INSTALL.md` and `docs/CONNECTION_SETUP.md`:
  take upstream's text, then rename as in section 5.
- `site/content.json` and `scripts/build_site.py`: TiPlay text, TiPlay links and no Telegram card.
  Resolve them as text, and take upstream's `VERSION`. Do not add the upstream Telegram keys again.
  Do not merge the generated `index.html` pages by hand. Generate them again and add them:

  ```sh
  python3 scripts/build_site.py
  git add site
  ```

- `.github/ISSUE_TEMPLATE/bug.yml`, `.github/ISSUE_TEMPLATE/config.yml`, `.github/workflows/build-android-tv.yml` and
  `SECURITY.md`: keep the TiPlay names and the `uJZk/DiPlay` links.

### Tests

- TiPlay added `useHeadUnitMode()` to the setup of several upstream tests, and TiPlay's controls to
  `AdaptiveSettingsUiTest`. Keep the changes of both sides.
- `common/src/test/resources/robolectric.properties` sets `TiPlayTestApplication`, so that no unit test opens the
  browser link's port. If upstream adds settings to this file, keep the `application` line.

### Changes that do not conflict but still break

- Upstream adds a class, file or resource with the same name as a TiPlay one.
  The build fails with a redeclaration or duplicate resource error. Rename the TiPlay one.
- Upstream adds a preference key that TiPlay already uses in `xcertplay_airplay` (`run_mode`, `car_bluetooth_audio`).
  Nothing fails, but both features read the same value. Rename the TiPlay key.
- Upstream adds a field to `AirPlayConfig` in `createAirPlayConfig()`. Add it to `phoneBrowserAirPlayConfig()`.
- Upstream adds head-unit behavior. Gate it as described in section 9.
- Upstream adds a locale folder. Add TiPlay's `strings_*.xml` files to it, translated.

## 5. Rename pass

1. List the new lines that say DiPlay or name DiPlay's application ID:

   ```sh
   git diff -U0 HEAD^1 HEAD |
     awk '/^\+\+\+ /{f=$2} /^\+[^+]/ && /DiPlay|com\.shihab\.diplay/{print f": "$0}'
   ```

   Run it right after the merge commit. `HEAD^1` is TiPlay's `main` before the merge, so the list shows only the lines
   that the merge brought in, each after its file name.
2. Write TiPlay where a user can see the name: string resources in all locales, Kotlin texts that a user sees
   (notifications, dialogs, toasts, the iPhone's accessory name, hotspot and file names), guides, `site/content.json`
   and the issue templates.
3. `com.shihab.diplay` as an application ID, for example in a package name check, becomes `com.ujzk.tiplay`.
   `com.shihab.diplay` as the start of an intent action stays (section 7).
4. Keep DiPlay in historical records: `docs/RELEASE-NOTES-0.2.*.md`, past `CHANGELOG.md` entries, audit, review and
   validation records such as `docs/VALIDATION.md` and `docs/ISSUE-AUDIT-2026-10-03.md`, the upstream README copies
   `docs/UPSTREAM-README.md` and `docs/UPSTREAM-README.zh-CN.md`, and every credit to DiPlay.
5. Keep DiPlay in Kotlin comments and KDoc, and in the internal names of section 7.
6. A bare issue number such as `#400` in a TiPlay guide points to TiPlay's tracker.
   Write it as a link to the upstream issue, for example `https://github.com/shihabal3amri/DiPlay/issues/400`.
   Past `CHANGELOG.md` entries keep bare numbers.
7. Run the brand test:

   ```sh
   ./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.BrandNameResourcesTest
   ```

   It fails when a string resource in `common` or `mobile` says DiPlay, except the About credit.
   It does not check Kotlin, documents or the site, so step 1 is still necessary.

**Do not use `sed` for DiPlay.**
The rename from TeslaPlay to TiPlay replaced every spelling with `sed`.
That was safe, because only TiPlay's own files and identifiers said TeslaPlay.
A replacement such as `sed -i 's/DiPlay/TiPlay/g'`, or a case-insensitive one, also changes upstream identifiers:
`DiPlayActivity`, the `diplay` preferences, the `com.shihab.diplay` intent actions, resource names such as
`about_diplay`, log tags and the credits.
Then saved settings or other apps' links break, and each later upstream change near those names conflicts.
Change each user-visible text by hand.

## 6. Resource strings

- TiPlay's strings are in their own files: `strings_tesla.xml`, `strings_tesla_link.xml`, `strings_car_audio.xml` and
  `strings_hotspot_address.xml`, in `common/src/main/res/values/` and in each `values-xx` folder.
  Upstream does not edit these files, so they do not conflict.
- Put each new TiPlay string in one of these files, or in a new `strings_<feature>.xml` file in every folder.
  Do not add TiPlay strings to upstream files such as `strings.xml`.
- Do not rename or delete an upstream string, even when TiPlay does not use it.
- If upstream adds a locale folder, add TiPlay's `strings_*.xml` files there, translated.
  `SettingsTranslationsTest` fails when a `settings_*` string has no translation. Other strings show in English.

## 7. Names that must stay

These upstream names stay unchanged, so that upstream changes still merge:

- Kotlin packages and namespaces: `com.shilapi.xcertplay`, and `com.diplay.home` and `com.diplay.maphost` for the samples.
- Class and file names, such as `DiPlayActivity`, `DiPlaySessionService`, `DiPlayPreferences` and `DiPlayBootstrap`.
- Android resource names, such as `about_diplay` and `ic_diplay_notification`.
- SharedPreferences files and keys, such as `xcertplay_airplay`, `diplay` and `diplay_*`. A new name loses saved settings.
- Log tags (`DiPlay-Video`, `DiPlayUi`) and thread names (`diplay-battery`).
- Intent actions: `com.shihab.diplay.action.EMBED_MAP` and `com.shihab.diplay.DISCONNECT`.
  TiPlay Home, the map host and other launchers use `EMBED_MAP`.
- `DIPLAY_AUTH_ASSETS_DIR` and `rootProject.name` (`xcertplay`).

These TiPlay names stay too:

- The application ID `com.ujzk.tiplay`. Debug builds add `.hudtest`.
- TiPlay's preference files, which start with `tiplay_` (for example `tiplay_browser_link`), and the `run_mode` and
  `car_bluetooth_audio` keys.
- The page's identifiers, such as the `tiplay.link` storage key and `window.tiplayStats`.

## 8. Checks

Run the CI command from `.github/workflows/android.yml`, the browser page tests and the public tree check:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
node --test tests/web/*.test.mjs
python3 scripts/check_public_tree.py
python3 scripts/build_site.py && git status --short site
```

After the site script, `git status` must list no file. A listed page was not generated again after the merge.
To check the page with a fake phone in headless Chromium, follow `tests/web-e2e/README.md`.

## 9. The phone + browser default

TiPlay starts in the phone + browser run mode. A merge must not change that.

- `PhoneBrowserModeSettingsTest` must pass. `AirPlayPersistence.loadRunMode` must still fall back to
  `CarPlayRunMode.PHONE_BROWSER`.
- New upstream head-unit behavior must stay off in phone + browser mode.
  This includes BYD outputs, the HUD and dashboard, wheel keys, ADB, start with the car, the car's home screen and
  vehicle data. Gate it with `config.headUnitIntegrations` in `CarPlayController`, with `headUnitIntegrations()` in
  `CarPlayHostActivity`, and with `AirPlayPersistence.isPhoneBrowserMode()` elsewhere.
- A new Settings card for head-unit hardware goes into `SettingsInformationArchitecture.headUnitOnlySections`.
- An upstream test that expects head-unit behavior can fail in the default mode. Call `useHeadUnitMode()` in its setup
  (`common/src/test/java/com/shilapi/xcertplay/HeadUnitRunMode.kt`). Do not change the default to make a test pass.
- On a phone, install a fresh debug build. Open **Settings → Connection → Tesla browser (experimental)**.
  **Phone and car browser** must be on.

## Checklist

- [ ] The push URL of `upstream` is `DISABLED`, and `upstream` fetches no tags.
- [ ] The merge is a merge commit on a branch. Nothing was rebased, squashed or cherry-picked.
- [ ] Each conflict keeps upstream's change and TiPlay's hook.
- [ ] `phoneBrowserAirPlayConfig()` sets every field of `createAirPlayConfig()`.
- [ ] New head-unit code is gated, and new head-unit cards are in `headUnitOnlySections`.
- [ ] No class, resource or preference key name is used twice.
- [ ] The new lines were checked for DiPlay. User-visible text says TiPlay. Identifiers, comments, credits and
      historical records keep DiPlay. No `sed` was used.
- [ ] New upstream issue numbers in guides link to DiPlay.
- [ ] The site pages were generated again.
- [ ] `README.md`, `README.zh-CN.md` and `CHANGELOG.md` name the merged DiPlay version.
- [ ] The CI command, the page tests and the public tree check pass.
- [ ] A fresh install starts in phone + browser mode.
- [ ] `main` moved forward to the branch and was pushed to `origin` only.
