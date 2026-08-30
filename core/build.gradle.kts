import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

/* Target 17 bytecode, which is what Android's toolchain accepts, but do not
   demand a 17 toolchain to produce it — this module has to build on whatever
   JDK a contributor happens to have, and 21 compiles 17 bytecode fine. */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies { testImplementation(kotlin("test")) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
