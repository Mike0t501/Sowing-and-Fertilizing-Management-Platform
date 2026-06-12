plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    signingConfigs {
    }
    namespace = "com.nx.vfremake"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nx.vfremake.v3"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
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
        buildConfigField(
            "String",
            "API_KEY",
            "\"AAPKfdce8f77ea174a8a8919d740a1cf8bf16BKmZwlbY1Ze5PpWzFPY7g5-OJk7QgCePqQF2w50H42MDUjg496CyFZP_ESGbhl_\""
        )
        buildConfigField(
            "String",
            "Lite_License",
            "\"runtimelite,1000,rud5900429336,none,B5H93PJPXJ0H8YAJM066\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // arcgis runtime for Android 库和Android serialport库依赖
    // https://mvnrepository.com/artifact/com.esri.arcgisruntime/arcgis-android
    //本地arcgis runtime api，没法用，arcgis需要调用drawable的文件，只有jar包是没法调用的，会报错
    implementation(libs.arcgis.android)
    // https://github.com/licheedev/Android-SerialPort-API
//    implementation("com.licheedev:android-serialport:2.1.3")
    implementation(files("libs/android-serialport-2.1.3-api.jar"))
}
