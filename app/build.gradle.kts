plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mayaa.subtransfer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mayaa.subtransfer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}

// The app UI is the repo-root index.html (also served by GitHub Pages); copy it into the APK's assets.
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("index.html"))
    into(layout.buildDirectory.dir("generated/webassets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
tasks.named("preBuild") { dependsOn(copyWeb) }
