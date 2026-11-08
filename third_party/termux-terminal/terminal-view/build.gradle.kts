plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.view"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
dependencies { api(project(":terminal-emulator")); implementation("androidx.annotation:annotation:1.9.1") }
