plugins {
    id("com.android.application") version "8.9.2"
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
}



val targetAbiFilters = providers.gradleProperty("targetAbi").orNull
    ?.takeUnless { it.equals("all", ignoreCase = true) }
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)

android {
    namespace = "com.infinimedia"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.infinimedia"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        targetAbiFilters?.let { filters -> ndk { abiFilters.addAll(filters) } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.pickFirsts += "**/libc++_shared.so"
    packaging.resources.merges += "META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider"
    packaging.resources.excludes += setOf(
        "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*",
        "META-INF/NOTICE*", "/META-INF/{AL2.0,LGPL2.1}"
    )
}

dependencies {
    implementation("io.github.gretchycat.libinfiniui:ui-android:0.1.0")
    implementation("io.github.gretchycat.libinfiniselect:select-android:0.1.0")
    implementation(project(":viewer-audio"))
    implementation(project(":viewer-video"))
}

val generatedIntentManifest = layout.buildDirectory.file("generated/android-intents/AndroidManifest.xml")
val generateIntentManifest by tasks.registering(Exec::class) {
    description = "Register file types reported by linked Scribe library capabilities"
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
    inputs.file(layout.projectDirectory.file("scripts/build-android-intent-manifest.py"))
    inputs.property("host", "infinimedia")
    outputs.file(generatedIntentManifest)
    outputs.file(generatedIntentManifest.map { it.asFile.resolveSibling("AndroidManifest.json") })
    commandLine(providers.gradleProperty("intentManifestPython").orElse("python3").get(),
        layout.projectDirectory.file("scripts/build-android-intent-manifest.py").asFile,
        "--template", layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile,
        "--output", generatedIntentManifest.get().asFile, "--host", "infinimedia")
}
android.sourceSets.getByName("main").manifest.srcFile(generatedIntentManifest)
tasks.named("preBuild").configure { dependsOn(generateIntentManifest) }

apply(from = layout.projectDirectory.file("scripts/runtime-capability-catalog.gradle"))

apply(from = rootProject.file("scripts/third-party-notices.gradle"))
