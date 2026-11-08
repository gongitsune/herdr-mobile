plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.terminal"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
dependencies { testImplementation("junit:junit:4.13.2"); implementation("androidx.annotation:annotation:1.9.1") }
