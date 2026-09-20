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
            kotlin.srcDir(layout.buildDirectory.dir("generated/kodikFixtures"))
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

// Compile the existing Android fixtures into common test strings: native has no JVM classloader.
val generateKodikFixtures by tasks.registering {
    val fixtures = rootProject.layout.projectDirectory.dir("android/src/test/resources/kodik")
    val output = layout.buildDirectory.dir("generated/kodikFixtures")
    inputs.dir(fixtures)
    outputs.dir(output)
    doLast {
        val file = output.get().file("app/kaeru/shared/data/kodik/KodikFixtures.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package app.kaeru.shared.data.kodik")
            appendLine("internal object KodikFixtures {")
            appendLine("fun text(name: String): String = kotlin.io.encoding.Base64.decode(when(name) {")
            fixtures.asFile.listFiles()!!.sortedBy { it.name }.forEach { fixture ->
                val chunks = Base64.getEncoder().encodeToString(fixture.readBytes()).chunked(8000)
                appendLine("\"${fixture.name}\" -> listOf(${chunks.joinToString { "\"$it\"" }}).joinToString(\"\")")
            }
            appendLine("else -> error(\"Unknown fixture: \$name\")")
            appendLine("}).decodeToString() }")
        })
    }
}
tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Test")) dependsOn(generateKodikFixtures)
}
