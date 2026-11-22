plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One version for the whole project, from the VERSION file at the repo root (e.g. 1.4.0 → code 10400).
val appVersion = rootProject.file("../VERSION").readText().trim()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

// The project's GitHub page for the in-app Help link (`make` and CI pass it; empty otherwise).
val projectUrl = (findProperty("projectUrl") as String?).orEmpty()
    .takeIf { Regex("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(it) }
    .orEmpty()

// Release signing key, from the environment (CI secrets) or ~/.gradle/gradle.properties. Without
// it, release builds are unsigned; debug builds always use the debug key.
fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: (findProperty(property) as String?)
val releaseKeystore = signingValue("SWAPBOARD_KEYSTORE", "swapboardKeystore")

android {
    namespace = "app.swapboard.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.swapboard.android"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "PROJECT_URL", "\"$projectUrl\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("SWAPBOARD_KEYSTORE_PASSWORD", "swapboardKeystorePassword")
                keyAlias = signingValue("SWAPBOARD_KEY_ALIAS", "swapboardKeyAlias") ?: "swapboard"
                keyPassword = signingValue("SWAPBOARD_KEY_PASSWORD", "swapboardKeyPassword") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Unit tests read the shared protocol test vectors from the repo root.
tasks.withType<Test>().configureEach {
    systemProperty(
        "swapboard.vectors",
        rootProject.layout.projectDirectory.file("../protocol/vectors.json").asFile.absolutePath,
    )
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.code.scanner)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
