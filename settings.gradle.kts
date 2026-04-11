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
        //arcgis和serialport maven链接
        maven { url = uri("https://esri.jfrog.io/artifactory/arcgis") }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Variablefert-Ultimate One"
include(":app")
 