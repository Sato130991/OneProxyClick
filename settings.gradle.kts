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

rootProject.name = "OneProxyClick"

include(
    ":app",
    ":core:common",
    ":core:ca-manager",
    ":core:proxy-engine",
    ":core:vpn-tunnel",
    ":core:interceptor",
    ":core:storage",
    ":feature:overlay",
    ":feature:repeater",
    ":feature:decoder",
)
