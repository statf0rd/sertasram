pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sertasram"

include("protocol", "signaling-server", "signaling-client", "media", "media-engine", "app-client")
