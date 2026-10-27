pluginManagement {
  repositories {
    // Local Gradle plugin marker overrides (Devin VM); ignored when absent.
    val localMarkers = java.io.File(System.getProperty("user.home") + "/.m2/plugin-markers")
    if (localMarkers.isDirectory) {
      maven { url = localMarkers.toURI() }
    }
    google()
    // JetBrains cache proxy of Maven Central as a fallback for Central rate limits (HTTP 429).
    maven("https://artifacts-caching-proxy.aws.intellij.net/maven-central")
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    // JetBrains cache proxy of Maven Central as a fallback for Central rate limits (HTTP 429).
    maven("https://artifacts-caching-proxy.aws.intellij.net/maven-central")
    mavenCentral()
  }
}

rootProject.name = "NetCraft"

include(":app")
include(":l2tp-core")
