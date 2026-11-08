plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    // Prevent an HC_IT=1 invocation from reusing the skipped integration-test result.
    inputs.property("HC_IT", providers.environmentVariable("HC_IT").orElse(""))
}

// This is a JVM jar, not an AAR: embed standard jar consumer rules for Android R8.
tasks.processResources {
    from("consumer-rules.pro") {
        into("META-INF/proguard")
        rename { "herdr-core-client.pro" }
    }
}
