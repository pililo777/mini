plugins {
    id("com.android.application")
}

android {
    namespace = "com.pililo777.minissh"
    compileSdk = 36

    flavorDimensions += "edition"

    productFlavors {
        create("stable") {
            dimension = "edition"
        }
        create("background") {
            dimension = "edition"
            applicationIdSuffix = ".background"
            versionNameSuffix = "-background"
        }
    }

    defaultConfig {
        applicationId = "com.pililo777.minissh"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4"
    }
}

configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.termux.termux-app:terminal-emulator:0.118.0")
    implementation("com.termux.termux-app:terminal-view:0.118.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.termux.termux-app:termux-shared:0.118.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.github.mwiede:jsch:2.28.4")
}
