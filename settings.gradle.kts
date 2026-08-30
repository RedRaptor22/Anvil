pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "anvil"

/* `core` is pure Kotlin and carries every piece of Plume that is not a screen:
   the frame maths, the stroke geometry, the sweep surface, the snap query. It
   has no Android dependency at all, which is the point — it compiles and its
   tests run on a plain JVM, so the port is verified without an emulator.

   `app` is the Android half: the GL renderer, the touch layer, the shell. It
   needs the Android SDK, so it is included only when one is present rather
   than breaking `./gradlew :core:test` for everyone else. */
include(":core")
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()) {
    include(":app")
}
