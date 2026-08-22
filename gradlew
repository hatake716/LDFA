#!/bin/sh
# Self-bootstrapping Gradle wrapper. The official wrapper JAR is downloaded once
# and verified against Gradle's published SHA-256 checksum.
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION="8.13"
GRADLE_GIT_TAG="v8.13.0"
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_CHECKSUM="$WRAPPER_DIR/gradle-wrapper-${GRADLE_VERSION}.jar.sha256"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/${GRADLE_GIT_TAG}/gradle/wrapper/gradle-wrapper.jar"
CHECKSUM_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-wrapper.jar.sha256"

checksum() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    else
        echo "SHA-256を計算できるコマンドがありません。" >&2
        exit 1
    fi
}

download() {
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --output "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --output-document="$2" "$1"
    else
        echo "Gradle Wrapperの取得にはcurlまたはwgetが必要です。" >&2
        exit 1
    fi
}

mkdir -p "$WRAPPER_DIR"
if [ ! -f "$WRAPPER_CHECKSUM" ] || ! grep -Eq '^[0-9a-fA-F]{64}[[:space:]]*$' "$WRAPPER_CHECKSUM"; then
    temp_checksum="$WRAPPER_CHECKSUM.tmp.$$"
    trap 'rm -f "$temp_checksum"' EXIT HUP INT TERM
    download "$CHECKSUM_URL" "$temp_checksum"
    tr -d '\r\n ' < "$temp_checksum" > "$WRAPPER_CHECKSUM"
    rm -f "$temp_checksum"
    trap - EXIT HUP INT TERM
fi

expected=$(tr -d '\r\n ' < "$WRAPPER_CHECKSUM")
actual=""
if [ -f "$WRAPPER_JAR" ]; then
    actual=$(checksum "$WRAPPER_JAR")
fi

if [ "$actual" != "$expected" ]; then
    temp="$WRAPPER_JAR.tmp.$$"
    trap 'rm -f "$temp"' EXIT HUP INT TERM
    echo "Gradle Wrapper ${GRADLE_VERSION}を検証付きで取得しています…" >&2
    download "$WRAPPER_URL" "$temp"
    actual=$(checksum "$temp")
    if [ "$actual" != "$expected" ]; then
        echo "Gradle WrapperのSHA-256が一致しません。" >&2
        exit 1
    fi
    mv "$temp" "$WRAPPER_JAR"
    trap - EXIT HUP INT TERM
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi
command -v "$JAVACMD" >/dev/null 2>&1 || {
    echo "Java 17以上が必要です。" >&2
    exit 1
}

exec "$JAVACMD" \
    -Dorg.gradle.appname=gradlew \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
