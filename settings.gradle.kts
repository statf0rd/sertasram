pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "stars"

include("protocol", "signaling-server", "signaling-client", "media", "media-engine", "app-client")
