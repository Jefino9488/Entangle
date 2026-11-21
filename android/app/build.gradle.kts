plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

val nativeAbis = providers.gradleProperty("rustAbis").orElse("arm64-v8a,x86_64")
val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", "infrastructure/android-build/build-rust.sh", nativeAbis.get())
    inputs.files(fileTree("../../rust") { exclude("target/**") })
    inputs.file("../../infrastructure/android-build/build-rust.sh")
    inputs.property("abis", nativeAbis)
    outputs.dir("src/main/jniLibs")
    outputs.dir("src/main/kotlin/uniffi")
}

android {
    namespace = "dev.entangle.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.entangle.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += nativeAbis.get().split(",") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(buildRust) }
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(buildRust)
}
ktlint {
    version.set("1.5.0")
    filter { exclude("**/uniffi/**") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

tasks.register("dependencyInventory") {
    description = "Record resolved Android runtime and test coordinates for license review."
    doLast {
        val coordinates = sortedMapOf<String, MutableSet<String>>()
        listOf("debugRuntimeClasspath", "debugAndroidTestRuntimeClasspath").forEach { scope ->
            configurations.getByName(scope).incoming.resolutionResult.allComponents.forEach { component ->
                component.moduleVersion?.let { module ->
                    if (component.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        coordinates.getOrPut("${module.group}:${module.name}:${module.version}") { sortedSetOf() }.add(scope)
                    }
                }
            }
        }
        val output = rootProject.file("../docs/android-dependency-coordinates.tsv")
        output.writeText(
            "coordinate\tscopes\n" + coordinates.entries.joinToString("\n") { "${it.key}\t${it.value.joinToString(",")}" } + "\n",
        )
        logger.lifecycle("Recorded ${coordinates.size} resolved dependencies in $output")
    }
}
