pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PineTimeHaBridge"

include(
    ":app",
    ":core-common",
    ":core-domain",
    ":core-data",
    ":core-ha",
    ":core-ble",
    ":core-ui",
)
