plugins {
    id("com.android.application")
}

android {
    namespace = "com.pililo777.minissh"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.pililo777.minissh"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.4")
}
