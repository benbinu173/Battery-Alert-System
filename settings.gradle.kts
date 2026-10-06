pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The USB-UART transport's driver library is published on JitPack and nowhere else.
        // Used for exactly one artifact (the `usb-serial` alias in libs.versions.toml);
        // nothing else in this project resolves through here.
        maven(url = "https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.mik3y.*")
            }
        }
    }
}

rootProject.name = "BatteryAlertSystem"
include(":app")
