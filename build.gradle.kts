plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// The pinned Termux and Termux:X11 sources predate the Android Gradle Plugin used by this
// application and currently trigger upstream-only lint errors such as MissingSuperCall. Keep
// running lint for these modules so reports remain available, but do not let pre-existing vendor
// findings hide regressions in :app, :termux-runtime, or :embedded-x11.
val vendoredAndroidLibraries = setOf(
    ":terminal-emulator",
    ":terminal-view",
    ":termux-shared",
    ":shell-loader-stub",
)

subprojects {
    if (path in vendoredAndroidLibraries) {
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                lint {
                    abortOnError = false
                }
            }
        }
    }
}
