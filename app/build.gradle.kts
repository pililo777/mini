plugins {
    id("com.android.application")
}

android {
    namespace = "com.pililo777.minissh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pililo777.minissh"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.4")
}
