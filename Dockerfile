FROM eclipse-temurin:25-jdk-noble

# The Android SDK's Linux build tools target x86_64. Build with --platform linux/amd64.
ARG ANDROID_COMMANDLINE_TOOLS=13114758
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/workspace/.gradle-user-home
ENV PATH="${ANDROID_HOME}/cmdline-tools/pinned/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip make python3 libc6 libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools/pinned" \
    && curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMANDLINE_TOOLS}_latest.zip" \
      -o /tmp/android-tools.zip \
    && unzip -q /tmp/android-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/pinned/" \
    && rm -rf /tmp/android-tools /tmp/android-tools.zip \
    && yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses > /dev/null
RUN sdkmanager --sdk_root="${ANDROID_HOME}" \
    "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"

WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties Makefile ./
COPY gradle/ gradle/
COPY app/build.gradle.kts app/gradle.lockfile app/proguard-rules.pro app/
COPY app/src/ app/src/
# Public values only, never supply a service-role key, OAuth secret, or session token.
ARG SUPABASE_URL=""
ARG SUPABASE_PUBLISHABLE_KEY=""
RUN chmod +x gradlew && make setup && make test

EXPOSE 8080
CMD ["python3", "-m", "http.server", "8080", "--directory", "app/build/outputs/apk/debug"]
