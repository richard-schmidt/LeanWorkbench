#!/data/data/com.termux/files/usr/bin/bash
# Lean Workbench build: hand-rolled aapt2 / kotlinc / d8 / apksigner pipeline,
# with Gradle used ONLY as a Maven dependency resolver.
# The ERR trap names the failing line (`set -e` in a bare VAR=$(...) aborts
# silently); "BUILD OK" is printed only by the last line.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

trap 'echo ""; echo "BUILD FAILED at ${BASH_SOURCE[0]}:${LINENO}: ${BASH_COMMAND}" >&2; exit 1' ERR

ANDROID_JAR="$HOME/android-sdk/platforms/android-34/android.jar"
OUT="$PROJECT_DIR/out"
APP_NAME="app"
KEYSTORE="$PROJECT_DIR/debug.keystore"
MIN_API=23

# No shared toolkit in this app. Kept as an empty, guarded variable so the
# Java pass-1 find below needs no special case.
TOOLKIT_SRC=""

die() { echo ""; echo "BUILD FAILED: $*" >&2; exit 1; }

# minSdkVersion in the manifest and d8's --min-api are two separate numbers
# that must agree; nothing checks this for you, so check it here.
MANIFEST_MIN=$(command grep -o 'android:minSdkVersion="[0-9]*"' AndroidManifest.xml | command grep -o '[0-9]*' | head -1)
[ "$MANIFEST_MIN" = "$MIN_API" ] || die "manifest minSdkVersion=$MANIFEST_MIN but build.sh MIN_API=$MIN_API"

# The keystore lives at the project root, NOT in $OUT: $OUT is wiped every
# build, and regenerating the signing key each run silently breaks in-place
# updates (Android refuses an APK signed by a different key).
if [ ! -f "$KEYSTORE" ]; then
  echo "== Generating persistent debug keystore =="
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" || die "keytool failed"
fi

echo "== XML comment preflight =="
# A literal double hyphen inside an XML comment fails aapt2 with a bare
# "not well formed (invalid token)" and no file:line, so check it first.
python3 "$PROJECT_DIR/check_xml_comments.py" "$PROJECT_DIR" || die "XML comment preflight failed"

DEPS_RAW="$PROJECT_DIR/deps/raw"
DEPS_EXTRACTED="$PROJECT_DIR/deps/extracted"

if [ -f "$PROJECT_DIR/build.gradle.kts" ]; then
  echo "== Resolving external dependencies (Gradle as resolver only) =="
  gradle resolveDeps --console=plain -q || die "gradle resolveDeps failed"

  rm -rf "$DEPS_EXTRACTED"
  mkdir -p "$DEPS_EXTRACTED"
  for artifact in "$DEPS_RAW"/*; do
    [ -e "$artifact" ] || continue
    name="$(basename "$artifact")"
    case "$name" in
      *.aar)
        dest="$DEPS_EXTRACTED/${name%.aar}"
        mkdir -p "$dest"
        unzip -q -o "$artifact" -d "$dest" || die "unzip $name failed"
        ;;
      *.jar)
        dest="$DEPS_EXTRACTED/${name%.jar}"
        mkdir -p "$dest"
        cp "$artifact" "$dest/classes.jar"
        ;;
    esac
  done
fi

EXTRA_JARS=()
EXTRA_RES_DIRS=()
EXTRA_PACKAGES=()
EXTRA_JNI_DIRS=()
EXTRA_MANIFESTS=()
if [ -d "$DEPS_EXTRACTED" ]; then
  for dir in "$DEPS_EXTRACTED"/*/; do
    [ -f "${dir}classes.jar" ] && EXTRA_JARS+=("${dir}classes.jar")
    if [ -d "${dir}res" ] && [ -n "$(find "${dir}res" -type f 2>/dev/null)" ]; then
      EXTRA_RES_DIRS+=("${dir}res")
    fi
    if [ -d "${dir}jni" ] && [ -n "$(find "${dir}jni" -name '*.so' 2>/dev/null)" ]; then
      EXTRA_JNI_DIRS+=("${dir}jni")
    fi
    if [ -f "${dir}AndroidManifest.xml" ]; then
      pkg="$(command grep -o 'package="[^"]*"' "${dir}AndroidManifest.xml" | head -1 | sed 's/package="//; s/"$//')"
      [ -n "$pkg" ] && EXTRA_PACKAGES+=("$pkg")
      EXTRA_MANIFESTS+=("${dir}AndroidManifest.xml")
    fi
  done
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/classes-kotlin" "$OUT/apk" "$OUT/res"

echo "== Compiling resources =="
aapt2 compile --dir res -o "$OUT/res/compiled.zip" || die "aapt2 compile (app res) failed"
RES_ZIPS=("$OUT/res/compiled.zip")
i=0
for resdir in "${EXTRA_RES_DIRS[@]}"; do
  i=$((i + 1))
  aapt2 compile --dir "$resdir" -o "$OUT/res/dep-$i.zip" || die "aapt2 compile ($resdir) failed"
  RES_ZIPS+=("$OUT/res/dep-$i.zip")
done

echo "== Merging manifests =="
MERGED_MANIFEST="$OUT/AndroidManifest.merged.xml"
if [ ${#EXTRA_MANIFESTS[@]} -gt 0 ]; then
  manifest_list_file="$OUT/dep-manifests.txt"
  printf '%s\n' "${EXTRA_MANIFESTS[@]}" > "$manifest_list_file"
  python3 merge_manifest.py AndroidManifest.xml "$manifest_list_file" "$MERGED_MANIFEST" || die "manifest merge failed"
else
  cp AndroidManifest.xml "$MERGED_MANIFEST"
fi

echo "== Linking resources =="
EXTRA_PACKAGES_ARG=()
for pkg in "${EXTRA_PACKAGES[@]}"; do
  EXTRA_PACKAGES_ARG+=(--extra-packages "$pkg")
done
aapt2 link -o "$OUT/apk/base.apk" \
  --manifest "$MERGED_MANIFEST" \
  -I "$ANDROID_JAR" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "${EXTRA_PACKAGES_ARG[@]}" \
  "${RES_ZIPS[@]}" || die "aapt2 link failed"

EXTRA_CP=""
for jar in "${EXTRA_JARS[@]}"; do
  EXTRA_CP="$EXTRA_CP:$jar"
done

# Hand-written Java that needs Kotlin's OUTPUT to compile. Everything else
# (R.java, the toolkit, pure-logic Java) compiles first so Kotlin can call
# it. A Java file and a Kotlin file cannot reference each other under this
# sequential structure; pick a direction.
JAVA_AFTER_KOTLIN=""
unset JAVA_HOME

echo "== Compiling Java, pass 1 (R.java + toolkit + Kotlin-independent) =="
JAVA_PASS1_SOURCES=$(find "$OUT/gen" ${TOOLKIT_SRC:+"$TOOLKIT_SRC"} src -name "*.java" $(for f in $JAVA_AFTER_KOTLIN; do echo "-not -name $f"; done)) \
  || die "find for Java pass 1 failed"
if [ -n "$JAVA_PASS1_SOURCES" ]; then
  # Bypassing the `ecj` wrapper on purpose: it puts Termux's own older
  # bundled android.jar ahead of ours on the classpath, shadowing anything
  # newer than that stub's API level.
  dalvikvm -Xmx256m \
    -Xcompiler-option --compiler-filter=speed \
    -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
    org.eclipse.jdt.internal.compiler.batch.Main \
    -proc:none -7 \
    -classpath "$ANDROID_JAR$EXTRA_CP" \
    -d "$OUT/classes" \
    $JAVA_PASS1_SOURCES || die "ecj (Java pass 1) failed"
fi

echo "== Compiling Kotlin (Compose) =="
# -jvm-target 11 is required, not stylistic: Compose AARs ship inline
# functions built for JVM 11 and Kotlin refuses to inline those into a
# JVM 1.8 target. android.jar must be on kotlinc's own -cp explicitly.
KOTLIN_PLUGIN="$PREFIX/opt/kotlin/lib/compose-compiler-plugin.jar"
[ -f "$KOTLIN_PLUGIN" ] || die "Compose compiler plugin not found at $KOTLIN_PLUGIN (pkg install kotlin)"
KOTLIN_SOURCES=$(find src -name "*.kt") || die "find for Kotlin sources failed"
if [ -n "$KOTLIN_SOURCES" ]; then
  kotlinc -Xplugin="$KOTLIN_PLUGIN" \
    -jvm-target 11 \
    -cp "$ANDROID_JAR:$OUT/classes$EXTRA_CP" \
    -d "$OUT/classes-kotlin" \
    $KOTLIN_SOURCES || die "kotlinc failed"
fi

echo "== Compiling Java, pass 2 (files that call into Kotlin) =="
JAVA_PASS2_SOURCES=$(for f in $JAVA_AFTER_KOTLIN; do find src -name "$f"; done)
if [ -n "$JAVA_PASS2_SOURCES" ]; then
  dalvikvm -Xmx256m \
    -Xcompiler-option --compiler-filter=speed \
    -cp /data/data/com.termux/files/usr/share/dex/ecj.jar \
    org.eclipse.jdt.internal.compiler.batch.Main \
    -proc:none -7 \
    -classpath "$ANDROID_JAR:$OUT/classes-kotlin$EXTRA_CP" \
    -d "$OUT/classes" \
    $JAVA_PASS2_SOURCES || die "ecj (Java pass 2) failed"
fi

echo "== Dexing =="
# The glob in the packaging step below, not a literal classes.dex, is what
# makes multidex safe: d8 splits past 65536 methods on its own and a
# packaging step that zips only classes.dex drops the rest SILENTLY.
d8 --output "$OUT/apk" --min-api "$MIN_API" \
  $(find "$OUT/classes" "$OUT/classes-kotlin" -name "*.class") \
  "${EXTRA_JARS[@]}" || die "d8 failed"

echo "== Packaging =="
cd "$OUT/apk"
cp base.apk "$APP_NAME-unsigned.apk"
zip -q -j "$APP_NAME-unsigned.apk" classes*.dex || die "zip (dex) failed"

if [ ${#EXTRA_JNI_DIRS[@]} -gt 0 ]; then
  LIB_STAGE="$OUT/lib-stage"
  rm -rf "$LIB_STAGE"
  mkdir -p "$LIB_STAGE"
  for jnidir in "${EXTRA_JNI_DIRS[@]}"; do
    cp -r "$jnidir"/. "$LIB_STAGE/"
  done
  mv "$LIB_STAGE" "$OUT/apk/lib"
  cd "$OUT/apk"
  zip -q -r "$APP_NAME-unsigned.apk" lib || die "zip (jni) failed"
fi

echo "== Signing =="
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$APP_NAME-signed.apk" "$APP_NAME-unsigned.apk" || die "apksigner sign failed"
apksigner verify "$APP_NAME-signed.apk" || die "apksigner verify failed"

echo ""
echo "Built: $OUT/apk/$APP_NAME-signed.apk"
echo "BUILD OK"
