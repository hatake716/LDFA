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
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "LinuxDesktopForAndroid"

include(":app")
include(":termux-runtime")
include(":embedded-x11")

include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("vendor/termux-app/terminal-emulator")

include(":terminal-view")
project(":terminal-view").projectDir = file("vendor/termux-app/terminal-view")

include(":termux-shared")
project(":termux-shared").projectDir = file("vendor/termux-app/termux-shared")

include(":shell-loader-stub")
project(":shell-loader-stub").projectDir = file("vendor/termux-x11/shell-loader/stub")
