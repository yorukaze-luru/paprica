#!/usr/bin/env bash
# JVM test for the PUSH-edition generator core: compiles the fork's
# app.webgen.core + TestPushMain and produces one audited sample signed
# by the apksig library (NOT the apksigner tool) with a key generated
# by SelfSignedCert into a scratch dir (persistence/reuse check
# included):
#   generator-push-app/sample-geckopush2.apk
#     package app.webgen.sample.geckopush2, templateVersion 2,
#     notifications ON, mic/camera/location OFF.
set -euo pipefail
ROOT="$HOME/workspace/web2apk"
FORK="$ROOT/generator-push-app"
export JAVA_HOME="$ROOT/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
APKSIGNER_JAR="$ROOT/tools/sdk/build-tools/34.0.0/lib/apksigner.jar"
WORK="$ROOT/build-generator-push-test"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/keys"
javac -encoding UTF-8 -cp "$APKSIGNER_JAR" -d "$WORK/classes" \
  "$FORK"/app/src/main/java/app/webgen/core/*.java \
  "$FORK/test/TestPushMain.java"
java -Xmx2g -cp "$WORK/classes:$APKSIGNER_JAR" TestPushMain \
  "$FORK/app/src/main/assets/template.apk" "$ROOT/sample_icon.png" "$WORK/keys" \
  "$FORK/sample-geckopush2.apk"

# Phase 5: two pushMode samples (hub + self config plumbing)
java -Xmx2g -cp "$WORK/classes:$APKSIGNER_JAR" TestPushMain \
  "$FORK/app/src/main/assets/template.apk" "$ROOT/sample_icon.png" "$WORK/keys" \
  "$FORK/sample-pushmode-hub.apk" hub app.webgen.sample.pushhub "プッシュ方式hub"
java -Xmx2g -cp "$WORK/classes:$APKSIGNER_JAR" TestPushMain \
  "$FORK/app/src/main/assets/template.apk" "$ROOT/sample_icon.png" "$WORK/keys" \
  "$FORK/sample-pushmode-self.apk" self app.webgen.sample.pushself "プッシュ方式self"
