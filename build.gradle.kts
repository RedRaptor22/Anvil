/* The root declares only what `core` needs. The Android Gradle Plugin is
   declared inside `app` instead, because `app` is only included when an
   Android SDK is present — resolving AGP here would make `./gradlew :core:test`
   fail on a machine that has no SDK and no need of one. */
plugins {
    kotlin("jvm") version "2.0.21" apply false
}
