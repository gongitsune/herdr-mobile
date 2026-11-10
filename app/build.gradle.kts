plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "io.herdrmobile.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.herdrmobile.android"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        versionName = rootProject.file("version.txt").takeIf { it.isFile }?.readText()?.trim() ?: "0.0.0"
    }
    signingConfigs {
        getByName("debug") {
            val env = listOf("HERDR_DEBUG_KEYSTORE", "HERDR_DEBUG_KEYSTORE_PASSWORD", "HERDR_DEBUG_KEY_ALIAS", "HERDR_DEBUG_KEY_PASSWORD")
                .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
            if (env.values.all { it != null }) {
                storeFile = file(env.getValue("HERDR_DEBUG_KEYSTORE")!!)
                storePassword = env.getValue("HERDR_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = env.getValue("HERDR_DEBUG_KEY_ALIAS")
                keyPassword = env.getValue("HERDR_DEBUG_KEY_PASSWORD")
            }
        }
    }
    packaging { resources.excludes += setOf("META-INF/versions/**/OSGI-INF/MANIFEST.MF") }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
    implementation(project(":terminal-view"))
    implementation(libs.compose.icons)
    implementation(libs.markdown.m3)
    implementation(libs.lifecycle.viewmodel)
    implementation(project(":core-client"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.compose)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
