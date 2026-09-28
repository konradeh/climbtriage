import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.climbtriage"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.climbtriage"
        minSdk = 28            // MediaMetadataRetriever.getFrameAtIndex
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
        // Default backend for the emulator (host loopback). Changeable in-app; never holds provider keys.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://10.0.2.2:8000\"")
        // MediaPipe ships native code for four ABIs; phones are arm64, emulators x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all {
            it.systemProperty("climbtriage.repoRoot", rootProject.projectDir.parentFile.absolutePath)
            // Optional live contract test against a running backend (skipped when unset).
            it.systemProperty("climbtriage.backendUrl", System.getenv("CLIMBTRIAGE_BACKEND_URL") ?: "")
        }
    }
}

/**
 * The MediaPipe pose model is downloaded at build time (Apache-2.0, see /NOTICE) rather than
 * committed. It is pinned by SHA-256 once known: set -PposeModelSha256=... to enforce it;
 * the build prints the digest it saw so the pin can be recorded.
 */
abstract class DownloadPoseModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input @get:Optional abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().file("pose_landmarker_full.task").asFile
        out.parentFile.mkdirs()
        if (!out.exists()) {
            URI(url.get()).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).joinToString("") { "%02x".format(it) }
        logger.lifecycle("pose_landmarker_full.task sha256=$digest (${out.length()} bytes)")
        val expected = sha256.orNull
        if (!expected.isNullOrBlank() && expected != digest) {
            out.delete()
            throw GradleException("pose model digest mismatch: expected $expected, got $digest")
        }
    }
}

val downloadPoseModel = tasks.register<DownloadPoseModel>("downloadPoseModel") {
    url.set("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task")
    providers.gradleProperty("poseModelSha256").orNull?.let { sha256.set(it) }
    outputDir.set(layout.buildDirectory.dir("generated/models"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadPoseModel, DownloadPoseModel::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
}
