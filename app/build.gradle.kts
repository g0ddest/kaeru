import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = "\"" + (localProps.getProperty(name) ?: "") + "\""

// Release signing lives outside the repository: ~/.kaeru/release.properties (or the file named by
// KAERU_RELEASE_PROPS in local.properties) holds KAERU_STORE_FILE / KAERU_STORE_PASSWORD /
// KAERU_KEY_ALIAS / KAERU_KEY_PASSWORD. Without it the release build type is simply unsigned.
val releaseProps = Properties().apply {
    val path = localProps.getProperty("KAERU_RELEASE_PROPS")
        ?: (System.getProperty("user.home") + "/.kaeru/release.properties")
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.kaeru"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kaeru"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SHIKIMORI_CLIENT_ID", secret("SHIKIMORI_CLIENT_ID"))
        buildConfigField("String", "KODIK_TOKEN", secret("KODIK_TOKEN"))
        // Kaeru's own worker, which holds the Shikimori client secret and exchanges codes for
        // tokens on the app's behalf: the secret is no longer compiled in here. Empty in a build
        // assembled without it, and an empty one means nobody can sign in — the app says so
        // rather than asking Shikimori for a token it has no secret to earn.
        buildConfigField("String", "AUTH_PROXY_URL", secret("AUTH_PROXY_URL"))
        // Where watch-together sessions meet when the two phones are not on one Wi-Fi. Empty in a
        // build assembled without it, and an empty one is not a broken relay but no relay at all:
        // the app says so and offers the local-network session instead of retrying forever.
        buildConfigField("String", "TOGETHER_RELAY_URL", secret("TOGETHER_RELAY_URL"))
    }

    signingConfigs {
        if (releaseProps.getProperty("KAERU_STORE_FILE") != null) {
            create("release") {
                storeFile = file(releaseProps.getProperty("KAERU_STORE_FILE"))
                storePassword = releaseProps.getProperty("KAERU_STORE_PASSWORD")
                keyAlias = releaseProps.getProperty("KAERU_KEY_ALIAS")
                keyPassword = releaseProps.getProperty("KAERU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The first hand-out build ships unminified: the R8 rules have not been smoke-tested on
            // a device yet, and ~30 MB is an acceptable price for a build that cannot break at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp {
    arg("room.generateKotlin", "true")
    // Exported from version 2 on. A migration can only be checked against the schema it is
    // migrating to, and there is no v1 schema to check the one this app already has — but every
    // migration after it will have one, which is the point of turning this on now rather than
    // when it is needed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.cast)
    implementation(libs.cast.framework)
    implementation(libs.mediarouter)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    // The six-hourly look for a new episode. The only thing in the app that has to happen while
    // nobody is holding the phone, which is exactly what WorkManager is and what a coroutine on
    // the application scope is not: the process is not running at four in the morning.
    implementation(libs.work.runtime)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    // Coil's disk cache is addressed by an okio path, so this one is used by name rather than
    // inherited: declared at the version everything else already resolves it to.
    implementation(libs.okio)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Composition tests under Robolectric: the focus a television screen starts on, and whether a
    // row a remote has to reach is reachable. Both are questions only a real composition answers.
    testImplementation(libs.compose.ui.test.junit4)
    // Debug rather than test: it contributes the bare ComponentActivity the compose rule launches,
    // and a manifest entry only reaches Robolectric through the application's own debug manifest.
    debugImplementation(libs.compose.ui.test.manifest)
}
