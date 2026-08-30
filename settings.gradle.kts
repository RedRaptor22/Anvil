pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    /* Versions live HERE, and the module build files apply them without one.
       Declaring `kotlin("jvm") version …` in the root instead puts the Kotlin
       plugin on the classpath with an unknown version, and :app's own
       `org.jetbrains.kotlin.android version …` then cannot be resolved at all:
       "already on the classpath with an unknown version, so compatibility
       cannot be checked". */
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("com.android.application") version "8.5.2"
    }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "anvil"

/* `core` is pure Kotlin and carries every piece of Plume that is not a screen:
   the frame maths, the stroke geometry, the snap query. No Android dependency
   at all, which is the point — it compiles and its tests run on a plain JVM.

   `app` is the Android half, and needs the SDK. Whether to include it is
   EXPLICIT rather than inferred: a CI runner has ANDROID_HOME set whether you
   wanted it or not, so "is there an SDK" silently pulled :app into the job that
   is supposed to prove core does not need one. `-PcoreOnly` says what is meant. */
include(":core")

val coreOnly = providers.gradleProperty("coreOnly").isPresent ||
    System.getenv("ANVIL_CORE_ONLY") == "1"
val haveSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (haveSdk && !coreOnly) include(":app")
