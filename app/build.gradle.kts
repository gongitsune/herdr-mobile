plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun gitOutput(vararg arguments: String): String? = runCatching {
    val output = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue == 0) output.standardOutput.asText.get().trim() else null
}.getOrNull()

if (providers.environmentVariable("CI").orNull.equals("true", ignoreCase = true) &&
    gitOutput("rev-parse", "--is-shallow-repository") == "true"
) {
    throw GradleException("CI requires full git history for versionCode. Set actions/checkout fetch-depth: 0.")
}

val appVersionCode = providers.environmentVariable("HERDR_VERSION_CODE").orNull?.let { value ->
    value.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("HERDR_VERSION_CODE must be a positive integer.")
} ?: (gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()?.takeIf { it > 0 } ?: 1)

android {
    namespace = "io.herdrmobile.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.herdrmobile.android"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
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
