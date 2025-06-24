pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        ivy {
            url = uri("https://nodejs.org/dist")
            name = "Node.js"
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            content {
                includeModule("org.nodejs", "node")
            }
        }
    }
}

rootProject.name = "NFCWalletDemo"
include(":app")