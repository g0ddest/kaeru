import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KaeruShared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/testFixtures"))
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.coroutines.test)
            }
        }
    }
}

android {
    namespace = "app.kaeru.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Ktor 3.6 asks for OkHttp 5.5, whose Android artifact insists on compileSdk 37 — a step past the
// AGP this project is pinned to. Every OkHttp artifact stays at the version the app ships;
// Ktor's engine is happy with any 5.x.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup.okhttp3" && requested.name.startsWith("okhttp")) {
            useVersion(libs.versions.okhttp.get())
        }
    }
}

// Compile the Android test fixtures into common test strings: native has no JVM classloader.
// Both modules read the same files, so a captured page or payload is fixed in one place.
val generateTestFixtures by tasks.registering {
    val fixtures = rootProject.layout.projectDirectory.dir("android/src/test/resources")
    val output = layout.buildDirectory.dir("generated/testFixtures")
    inputs.dir(fixtures)
    outputs.dir(output)
    doLast {
        val root = fixtures.asFile
        val files = listOf("kodik", "shikimori").flatMap { dir ->
            root.resolve(dir).listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        }
        val file = output.get().file("app/kaeru/shared/TestFixtures.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package app.kaeru.shared")
            appendLine("internal object TestFixtures {")
            appendLine("/** A fixture by its path under `android/src/test/resources`, e.g. `kodik/player.html`. */")
            appendLine("fun text(name: String): String = kotlin.io.encoding.Base64.decode(when(name) {")
            files.forEach { fixture ->
                val chunks = Base64.getEncoder().encodeToString(fixture.readBytes()).chunked(8000)
                val key = fixture.relativeTo(root).path.replace('\\', '/')
                appendLine("\"$key\" -> listOf(${chunks.joinToString { "\"$it\"" }}).joinToString(\"\")")
            }
            appendLine("else -> error(\"Unknown fixture: \$name\")")
            appendLine("}).decodeToString() }")
        })
    }
}
tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Test")) dependsOn(generateTestFixtures)
}
