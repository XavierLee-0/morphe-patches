# Morphe Patches build image: JDK 21 + Android SDK (platform 36)
# Usage (on the LAN Linux server, inside the repo root):
#   docker build -t morphe-builder .
#   docker run --rm -v "$PWD":/repo -w /repo morphe-builder ./gradlew :patches:build --no-daemon
# Artifact: patches/build/libs/patches-*.mpp
FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget unzip \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
    && ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" > /dev/null
