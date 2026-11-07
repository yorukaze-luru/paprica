#!/usr/bin/env bash
# Manual PUSH-edition generator APK build pipeline (fork of
# build_generator.sh for generator-push-app/).
#
# Same manual pipeline as the regular edition: aapt2 compile/link ->
# kotlinc -> d8 (app classes + kotlin-stdlib + apksig classes) ->
# zipalign -> apksigner. No Gradle daemon.
# Output: ~/workspace/web2apk/dist/paprica-v1.2.0.apk
# The push-edition generator APK itself is signed with the keystore in
# KS (the project's ongoing release key; see build_generator.sh).
# The APKs this generator *produces* are signed on-device with the
# push generator's own self-signed key (kept in THIS app's private
# storage) via apksig — never with this key, and never shared with
# the regular edition's on-device key.
set -euo pipefail

ROOT="$HOME/workspace/web2apk"
TOOLS="$ROOT/tools"
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$TOOLS/kotlinc/bin:$PATH"

ANDROID_JAR="$TOOLS/sdk/platforms/android-34/android.jar"
BT="$TOOLS/sdk/build-tools/34.0.0"
APP="$ROOT/generator-push-app/app"
WORK="$ROOT/build-generator-push"
KS="$ROOT/test-keystore-TEST-ONLY.jks"
# Signing key password: overridable via env so the script stays publishable
# without embedding the secret (default is the local test value).
KS_PASS="${WEB2APK_KS_PASS:-change-me}"
APKSIG_JAR="$ROOT/generator-push-app/libs/apksig-only.jar"
OUT="$ROOT/dist/paprica-v1.2.0.apk"

# --- 0. apksig-only jar (extract com/android/apksig/** from SDK apksigner.jar)
if [ ! -f "$APKSIG_JAR" ]; then
  mkdir -p "$ROOT/generator-push-app/libs" "$WORK/apksig-extract"
  (cd "$WORK/apksig-extract" && unzip -o -q "$BT/lib/apksigner.jar" "com/android/apksig/*" \
    && zip -q -r "$APKSIG_JAR" com)
  echo "extracted apksig-only.jar"
fi

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/classes" "$WORK/dex"

echo "== 1. aapt2 compile resources =="
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$WORK/compiled/res.zip"

echo "== 2. aapt2 link (manifest + resources + assets incl. gecko template.apk) =="
# NOTE: --custom-package app.webgen.generator — the manifest package
# (applicationId) is app.webgen.generator.push, but the Kotlin/Java
# sources keep their original package app.webgen.generator (class
# names in the manifest are fully qualified and unchanged), so the
# generated R class must be emitted into the source package.
"$BT/aapt2" link \
  -o "$WORK/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --custom-package app.webgen.generator \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  -A "$APP/src/main/assets" \
  --java "$WORK/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 5 --version-name 1.2.0 \
  "$WORK/compiled/res.zip"

echo "== 3. kotlinc (no daemon; Kotlin + Java mixed sources + generated R) =="
kotlinc -J-Dkotlin.daemon.enabled=false \
  -classpath "$ANDROID_JAR:$APKSIG_JAR" \
  -d "$WORK/classes" \
  $(find "$APP/src/main/java" -name '*.kt' -o -name '*.java') \
  $(find "$WORK/gen" -name '*.java')

echo "== 3b. javac (Java sources: core, provider, generated R) =="
javac -encoding UTF-8 \
  -cp "$ANDROID_JAR:$APKSIG_JAR:$WORK/classes" \
  -d "$WORK/classes" \
  $(find "$APP/src/main/java" -name '*.java') \
  $(find "$WORK/gen" -name '*.java')

echo "== 4. d8 -> classes.dex (app + kotlin-stdlib + apksig) =="
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') \
  "$TOOLS/kotlinc/lib/kotlin-stdlib.jar" \
  "$APKSIG_JAR"

echo "== 5. add dex, zipalign =="
cp "$WORK/unsigned.apk" "$WORK/with-dex.apk"
(cd "$WORK/dex" && zip -q "$WORK/with-dex.apk" classes.dex)
"$BT/zipalign" -f 4 "$WORK/with-dex.apk" "$WORK/aligned.apk"

echo "== 6. apksigner sign paprica-v1.2.0.apk (release keystore) =="
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk"

echo "== done =="
ls -la "$OUT"
"$BT/apksigner" verify --verbose "$OUT" | head -6
"$BT/aapt2" dump badging "$OUT" | grep -E "^(package:|application-label:|application:)" | head -3
