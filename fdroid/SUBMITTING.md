# Submitting LiteTube to F-Droid

Everything F-Droid needs is already in this repository:

| Requirement (F-Droid Inclusion Policy / Quick Start Guide) | Where |
|---|---|
| Free license | `LICENSE` (GPL-3.0-or-later) |
| Only free dependencies, from trusted Maven repos (Maven Central, Google, JitPack) | `app/build.gradle.kts` |
| No tracking, ads, Google Play Services or Firebase | ✓ |
| No Google "dependency info" blob in the APK | `dependenciesInfo` in `app/build.gradle.kts` |
| Unsigned release build (F-Droid signs it) | default unless `LITETUBE_KEYSTORE` / `LITETUBE_SIGN_WITH_DEBUG_KEY` is set |
| Application ID from a domain the developer controls | `io.github.milutinbasic_lang.litetube` (from milutinbasic-lang.github.io) |
| Store texts, icon, feature graphic, screenshots | `fastlane/metadata/android/en-US/` |
| Changelog for each `versionCode` | `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` |
| A git tag for each release (`v` + versionName) | `v0.2.0` |
| Anti-feature disclosed: depends on YouTube (NonFreeNet) | `fdroid/io.github.milutinbasic_lang.litetube.yml` |
| Build recipe for the fdroiddata merge request | `fdroid/io.github.milutinbasic_lang.litetube.yml` |

## Steps (about 15 minutes)

0. **Create the release tag** (F-Droid builds from it). On GitHub open the repository →
   **Releases** → **Draft a new release** → **Choose a tag** → type `v0.2.0` →
   *Create new tag on publish*, target `main` → title `LiteTube 0.2.0` → **Publish release**.
   (Optional cleanup: **Branches** → delete `ci-screenshots`, a leftover from capturing the
   screenshots.)
1. **Create a GitLab account** at https://gitlab.com (F-Droid's packaging lives there).
2. **Fork** https://gitlab.com/fdroid/fdroiddata (button "Fork" at the top right).
3. In your fork, **create a branch** named `io.github.milutinbasic_lang.litetube`.
4. In that branch, go to the `metadata` folder → **New file**, name it
   `io.github.milutinbasic_lang.litetube.yml`, and paste the contents of
   [`fdroid/io.github.milutinbasic_lang.litetube.yml`](io.github.milutinbasic_lang.litetube.yml).
   Commit it.
5. Open **Build → Pipelines** in your fork and wait for the pipeline to pass. If a job fails,
   open its log; the most common fixes are formatting (the `fdroid lint` job tells you exactly
   what to change).
6. Create a **merge request** from your branch to `fdroid/fdroiddata` `master`. Fill in the
   template; mention that you are the author of the app.
7. Answer reviewer questions on the merge request. Inclusion usually takes days to a few weeks.

Alternatively, open a "Request for Packaging" issue at https://gitlab.com/fdroid/rfp/-/issues
and a volunteer may do steps 2–6 for you (slower).

## Things reviewers may ask about

- **Unique value.** F-Droid already has NewPipe, Tubular, LibreTube and others, and the policy says
  apps should "provide some unique value". The description leads with what is different here:
  built for low-end phones (Android 5+, H.264 ≤30fps by default, small buffers, audio-only
  background decoding), plus on-device summaries.
- **Name.** "LiteTube" must not infringe trademarks. It doesn't use YouTube's name or logo, but
  if a reviewer objects, renaming is easy (`app_name` in `strings.xml` and `title.txt`).

## Releasing updates afterwards

With `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, F-Droid picks up new releases
automatically:

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters).
3. Commit, then tag the commit `v<versionName>` (e.g. `v0.3.0`) and push the tag.

Never reuse a versionCode, and never change the application ID: installed copies could not update.
