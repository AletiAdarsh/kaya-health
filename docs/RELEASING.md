# Releasing Kaya

A checklist for publishing a version on GitHub.

## One-time setup

1. **Create a release key** (keep it forever; losing it means users must uninstall to update):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias kaya -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then create `keystore.properties` in the project root (both files are gitignored):
   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=kaya
   keyPassword=...
   ```
   Back up `release.jks` and the passwords somewhere safe outside the repo.
2. **Check the repo is clean** before making it public: no personal data, captures, logs or vendor files.
   `git log --stat` over the whole history, not just the latest commit.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest assembleRelease`
3. Install `app/build/outputs/apk/release/app-release.apk` on a phone and smoke-test: setup, band connect,
   live heart rate, food log, backup and restore.
4. Tag and publish:
   ```bash
   git tag v0.15.0 && git push origin v0.15.0
   gh release create v0.15.0 app/build/outputs/apk/release/app-release.apk --title "Kaya 0.15.0" --notes "..."
   ```
