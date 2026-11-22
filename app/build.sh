#!/usr/bin/env bash
# Muse Wear Installer Phone — raw-toolchain build (no Gradle, no
# AndroidX app code), following the fleet pattern of Muse Device
# Assistance and Muse Wear Installer: aapt2 -> javac -> d8 ->
# zipalign -> apksigner, with a project-local keystore so upgrades
# install in place.
#
# Vendored libraries (app/libs, provenance in app/libs-src):
#   play-services-wearable 19.0.0 + base 18.5.0 + basement 18.4.0 +
#   tasks 18.2.0 (GMS Data Layer), androidx.annotation 1.9.1 (d8
#   strips it; annotations never execute), androidx.collection 1.1.0
#   (GMS IPC internals use ArrayMap — vendored as the pure-Java
#   1.1.0; the 1.4.x collection jar on Google Maven is Kotlin-
#   multiplatform metadata with zero Android classes). No other
#   AndroidX is linked — the guards below enforce that, with one
#   documented exception: GMS-internal error-dialog code references
#   androidx.activity / androidx.core / androidx.fragment classes
#   that the Data Layer IPC path never loads; those dangling
#   references are counted and logged, not silently passed.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/workspace/meta-muse-widget/tools/sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
JDK="${JAVA_HOME:-$HOME/workspace/custom-skills/local-rea/tools/jdk}"
export PATH="$JDK/bin:$PATH"

APP_ID="com.jonathancruzate.metamusewearinstallerphone"
VERSION_CODE=4
VERSION_NAME="1.0.3"
MIN_SDK=29
TARGET_SDK=35

KS_DIR="keystore"
KS="$KS_DIR/musewearinstallerphone.keystore"
KS_PASS_FILE="$KS_DIR/keystore.pass"
mkdir -p "$KS_DIR"
if [ ! -f "$KS" ]; then
  PASS="$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  printf '%s' "$PASS" > "$KS_PASS_FILE"
  chmod 600 "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias musewearinstallerphone -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Muse Wear Installer Phone, O=Jonathan Cruzate, C=US" >/dev/null 2>&1
  echo "created project keystore: $KS"
fi
KS_PASS="$(cat "$KS_PASS_FILE")"

LIB_JARS="$(ls libs/*.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')"
CP="$ANDROID_JAR"
[ -n "$LIB_JARS" ] && CP="$CP:$LIB_JARS"

rm -rf build
mkdir -p build/compiled build/gen build/classes build/dex

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir res -o build/compiled/res.zip

echo "== aapt2 link =="
"$BT/aapt2" link \
  -o build/app-unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java build/gen \
  --auto-add-overlay \
  build/compiled/res.zip

echo "== javac =="
find src build/gen -name '*.java' > build/sources.txt
# shellcheck disable=SC2086
javac -source 11 -target 11 -cp "$CP" -d build/classes @build/sources.txt

echo "== guard: every app class compiled non-empty =="
CLASS_COUNT="$(find "build/classes/${APP_ID//.//}" -name '*.class' | wc -l)"
if [ "$CLASS_COUNT" -lt 10 ]; then
  echo "GUARD FAIL: only $CLASS_COUNT app classes compiled" >&2; exit 1
fi
echo "app classes: $CLASS_COUNT"

echo "== d8 (min-api $MIN_SDK) =="
find build/classes -name '*.class' > build/classes.txt
# shellcheck disable=SC2086
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output build/dex $(cat build/classes.txt) libs/*.jar

echo "== package dex =="
cp build/app-unsigned.apk build/app-staged.apk
(cd build/dex && zip -q ../app-staged.apk *.dex)

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 build/app-staged.apk build/app-aligned.apk
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "MuseWearInstallerPhone-v${VERSION_NAME}.apk" build/app-aligned.apk
"$BT/apksigner" verify --print-certs "MuseWearInstallerPhone-v${VERSION_NAME}.apk" | head -3

echo "== guards =="
DEXDUMP="$BT/dexdump"
FAIL=0
for dex in build/dex/*.dex; do
  "$DEXDUMP" -f "$dex" > "$dex.txt" 2>/dev/null || true
done
# (a) every app class must be DEFINED in the dex set
for src in $(find src -name '*.java'); do
  cls="$(basename "$src" .java)"
  if ! grep -q "Class descriptor  : 'L${APP_ID//.//}/$cls;'" build/dex/*.txt; then
    echo "GUARD FAIL: app class missing from dex: $cls"; FAIL=1
  fi
done
# (b) the GMS classes this app binds to must be DEFINED
for fqcn in \
  com/google/android/gms/wearable/Wearable \
  com/google/android/gms/wearable/WearableListenerService \
  com/google/android/gms/wearable/ChannelClient \
  com/google/android/gms/wearable/MessageClient \
  com/google/android/gms/wearable/NodeClient \
  com/google/android/gms/tasks/Tasks \
  androidx/collection/ArrayMap; do
  if ! grep -q "Class descriptor  : 'L$fqcn;'" build/dex/*.txt; then
    echo "GUARD FAIL: library class missing from dex: $fqcn"; FAIL=1
  fi
done
# (c) DEFINED androidx classes may only live under annotation/collection
if grep "Class descriptor  : 'Landroidx/" build/dex/*.txt \
     | grep -vE "'Landroidx/(annotation|collection)/"; then
  echo "GUARD FAIL: unexpected androidx class defined (above)"; FAIL=1
fi
# (d) app classes must not reference androidx outside annotation
for txt in build/dex/*.txt; do
  awk -v pkg="${APP_ID//.//}" '
    /^Class #[0-9]+/ {cur=""}
    /Class descriptor/ {cur=$0}
    cur ~ ("L" pkg "/") && /Landroidx\// && !/Landroidx\/annotation\// {print FILENAME": "$0"  <<<  "cur; bad=1}
    END {exit bad}
  ' "$txt" || { echo "GUARD FAIL: app class references androidx beyond annotation (above)"; FAIL=1; }
done
# (e) dangling GMS-internal androidx references: counted + logged
DANGLING="$(grep -hoE 'Landroidx/(activity|core|fragment)/[A-Za-z0-9/$]+' build/dex/*.txt | sort -u | wc -l)"
echo "note: $DANGLING distinct dangling androidx activity/core/fragment references (GMS dialog code, never loaded by the Data Layer path)"
[ "$FAIL" = 0 ] || { echo "BUILD GUARDS FAILED"; exit 1; }

SHA="$(sha256sum "MuseWearInstallerPhone-v${VERSION_NAME}.apk" | cut -d' ' -f1)"
SIZE="$(stat -c%s "MuseWearInstallerPhone-v${VERSION_NAME}.apk")"
echo "BUILT MuseWearInstallerPhone-v${VERSION_NAME}.apk size=$SIZE sha256=$SHA"
