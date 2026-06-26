layout.buildDirectory.set(file("D:/development/Android/build-corn-seedfert-monitor-ui467"))

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

val localProperties = java.util.Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun quotedLocalProperty(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name).orEmpty())
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    signingConfigs {
    }
    namespace = "com.nx.vfremake"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nx.corn.seedfert.monitor"
        minSdk = 29
        targetSdk = 34
        versionCode = 19
        versionName = "4.6.7-frameless-testbar"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(arrayOf("armeabi-v7a", "arm64-v8a"))
        }

        kotlinOptions {
            jvmTarget = "1.8"
        }
        buildFeatures {
            buildConfig = true
            compose = true
        }
        composeOptions {
            kotlinCompilerExtensionVersion = "1.5.3"
        }
        buildConfigField("String", "API_KEY", "\"${quotedLocalProperty("ARCGIS_API_KEY")}\"")
        buildConfigField("String", "Lite_License", "\"${quotedLocalProperty("ARCGIS_LITE_LICENSE")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources.excludes.add("META-INF/DEPENDENCIES")
    }
    ndkVersion = "27.0.11718014 rc1"
    buildToolsVersion = "34.0.0"


}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.play.services.maps)
    implementation(libs.core.ktx)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    //... Compose
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.runtime.rxjava2)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.material)
    implementation(libs.androidx.material.icons.extended)
    //...

    //... navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    //...

    //... lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    //...

    implementation (libs.gson)

    // ArcGIS Runtime and Android serial port dependencies.
    // https://mvnrepository.com/artifact/com.esri.arcgisruntime/arcgis-android
    // Use Maven ArcGIS runtime because the local jar cannot load drawable resources.
    implementation(libs.arcgis.android)
    // https://github.com/licheedev/Android-SerialPort-API
//    implementation("com.licheedev:android-serialport:2.1.3")
    implementation(files("libs/android-serialport-2.1.3-api.jar"))
}
