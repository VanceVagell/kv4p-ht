# Use AMD64 architecture explicitly for Android SDK compatibility
FROM --platform=linux/amd64 mcr.microsoft.com/devcontainers/java:1-17-bookworm

# Set environment variables
ENV DEBIAN_FRONTEND=noninteractive

# Install Node.js 20
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Install Android SDK
ENV ANDROID_HOME=/usr/local/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && cd ${ANDROID_HOME}/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip commandlinetools-linux-*_latest.zip \
    && rm commandlinetools-linux-*_latest.zip \
    && mv cmdline-tools latest \
    && yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses \
    && ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Set JAVA_HOME
ENV JAVA_HOME=/usr/lib/jvm/msopenjdk-current
