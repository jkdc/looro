# Android JVM tests in Docker

This runner pins Eclipse Temurin JDK 17, Android Command-line Tools revision
15859902 (SHA-256 verified), Android platform 36, Build Tools 35.0.0, and uses
the repository Gradle Wrapper (Gradle 8.11.1). AGP 8.10.1 requires JDK 17;
the image also pins Android NDK 27.0.12077973 and CMake 3.22.1 for the local
engine module. These unit-test tasks need the Android SDK but do not launch an emulator.

From the repository root, run:

```sh
apps/android/docker/test-unit.sh
```

The script builds `looro-android-test:agp-8.10.1-sdk-36`, then runs
`:app:testDebugUnitTest` and `:core:engine-local:testDebugUnitTest` inside that
container. Gradle distributions, dependencies, and transforms persist in the
Docker volume `looro-gradle-cache`; the SDK is pinned in the image. No JDK,
Gradle, or Android SDK is installed on the host. The repository is bind-mounted
only for Gradle inputs and outputs. The first build/test needs network access to
fetch the image base, SDK packages, Gradle distribution, and Maven dependencies;
subsequent invocations reuse the image and Gradle cache.

To run only the T03 app database suite after the first invocation, use the same
container setup and replace the final Gradle task list with:

```sh
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.jkdc.app.data.LearningDbTest' --tests 'com.jkdc.app.data.BackupManagerTest' --tests 'com.jkdc.app.data.LearningCutoverFlowTest'
```

The full JVM suite is preferred for T03 closure because it compiles all app
production/test sources and catches cross-test regressions. UI instrumentation
and emulator tests are intentionally excluded.
