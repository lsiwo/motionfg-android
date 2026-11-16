#!/usr/bin/env bash
set -euo pipefail

project=$(cd -- "$(dirname -- "$0")" && pwd)
absolute_path() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s/%s\n' "$PWD" "$1" ;;
    esac
}

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
android_jar=${ANDROID_JAR:-"${sdk}/platforms/android-35/android.jar"}
tools=${ANDROID_BUILD_TOOLS:-"${sdk}/build-tools/35.0.1"}
output=${1:-"$project/MotionFG-Android.apk"}

if [[ ! -f "$android_jar" || ! -x "$tools/aapt2" || ! -x "$tools/d8" ||
      ! -x "$tools/zipalign" || ! -x "$tools/apksigner" || ! -f "$tools/core-lambda-stubs.jar" ]]; then
    printf '%s\n' 'Install Android SDK Platform 35 and Build Tools 35.0.1, then set ANDROID_HOME.' >&2
    exit 1
fi
for command in java zip; do
    if ! command -v "$command" >/dev/null; then
        printf 'Missing build command: %s\n' "$command" >&2
        exit 1
    fi
done
if [[ -n ${ECJ_JAR:-} ]]; then
    if [[ ! -f "$ECJ_JAR" ]]; then
        printf '%s\n' 'ECJ_JAR does not point to a compiler JAR.' >&2
        exit 1
    fi
elif ! command -v javac >/dev/null; then
    printf '%s\n' 'Install JDK 17 or newer and add javac to PATH.' >&2
    exit 1
fi

signing_key=${MOTION_FG_KEYSTORE:-"$project/motionfg-local.p12"}
key_alias=${MOTION_FG_KEY_ALIAS:-motionfg}
if [[ -n ${MOTION_FG_KEYSTORE:-} && ! -f "$signing_key" ]]; then
    printf '%s\n' 'MOTION_FG_KEYSTORE does not point to an existing signing key.' >&2
    exit 1
fi
output=$(absolute_path "$output")
android_jar=$(absolute_path "$android_jar")
tools=$(absolute_path "$tools")
signing_key=$(absolute_path "$signing_key")
if [[ -n ${ECJ_JAR:-} ]]; then ECJ_JAR=$(absolute_path "$ECJ_JAR"); fi
bootclasspath="$android_jar:$tools/core-lambda-stubs.jar"
export MOTION_FG_STORE_PASS=${MOTION_FG_STORE_PASS:-android}
export MOTION_FG_KEY_PASS=${MOTION_FG_KEY_PASS:-$MOTION_FG_STORE_PASS}

build=$(absolute_path "$(mktemp -d)")
signed=
cleanup() {
    rm -rf -- "$build"
    if [[ -n "$signed" ]]; then rm -f -- "$signed"; fi
}
trap cleanup EXIT
mkdir -p "$build/gen" "$build/classes" "$build/dex"
mkdir -p "$(dirname -- "$output")"

"$tools/aapt2" compile --dir "$project/app/res" -o "$build/compiled.zip"
"$tools/aapt2" link -I "$android_jar" --manifest "$project/app/AndroidManifest.xml" \
    --java "$build/gen" -A "$project/app/assets" -o "$build/resources.apk" "$build/compiled.zip"

{
    cd "$project"
    find app/java "$build/gen" -name '*.java' | LC_ALL=C sort |
        while IFS= read -r source; do printf '"%s"\n' "$source"; done
} > "$build/sources"
cd "$project"
if [[ -n ${ECJ_JAR:-} ]]; then
    java -jar "$ECJ_JAR" -8 -encoding UTF-8 -proc:none -nowarn \
        -bootclasspath "$bootclasspath" -d "$build/classes" @"$build/sources"
else
    javac -source 8 -target 8 -encoding UTF-8 -proc:none \
        -bootclasspath "$bootclasspath" -d "$build/classes" @"$build/sources"
fi

(cd "$build/classes" && zip -qr "$build/java.jar" .)
"$tools/d8" --release --min-api 34 --lib "$android_jar" --output "$build/dex" "$build/java.jar"
(
    cd "$build/dex"
    zip -q "$build/resources.apk" classes*.dex
)
"$tools/zipalign" 4 "$build/resources.apk" "$build/aligned.apk"

if [[ ! -f "$signing_key" ]]; then
    (
        umask 077
        keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$signing_key" \
            -storepass:env MOTION_FG_STORE_PASS -alias "$key_alias" -keyalg RSA -keysize 2048 \
            -validity 10000 -dname 'CN=Motion FG Local Build'
    )
fi
signed=$(mktemp "$(dirname -- "$output")/.motionfg-XXXXXX.apk")
"$tools/apksigner" sign --ks "$signing_key" --ks-key-alias "$key_alias" \
    --ks-pass env:MOTION_FG_STORE_PASS --key-pass env:MOTION_FG_KEY_PASS \
    --debuggable-apk-permitted false --v4-signing-enabled false --out "$signed" "$build/aligned.apk"
"$tools/apksigner" verify "$signed"
chmod 644 "$signed"
mv -f -- "$signed" "$output"
