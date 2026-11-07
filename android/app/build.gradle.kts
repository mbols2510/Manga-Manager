import com.android.build.api.artifact.SingleArtifact

// AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android is not applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mbols2510.mangamanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mbols2510.mangamanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// Spec 7.4: release must stay non-debuggable with R8 and resource shrinking on.
// Values are read eagerly (set just above) so the task is configuration-cache safe.
val releaseType = android.buildTypes.getByName("release")
val releaseHardening: Map<String, Boolean> = mapOf(
    "isDebuggable" to releaseType.isDebuggable,
    "isJniDebuggable" to releaseType.isJniDebuggable,
    "isMinifyEnabled" to releaseType.isMinifyEnabled,
    "isShrinkResources" to releaseType.isShrinkResources,
)
tasks.register("verifyReleaseHardening") {
    group = "verification"
    description = "Fails if the release build type is debuggable or not shrunk by R8."
    val s = releaseHardening
    inputs.property("releaseHardening", s)
    doLast {
        val problems = buildList {
            if (s["isDebuggable"] != false) add("release is debuggable")
            if (s["isJniDebuggable"] != false) add("release is JNI-debuggable")
            if (s["isMinifyEnabled"] != true) add("R8 minification is off")
            if (s["isShrinkResources"] != true) add("resource shrinking is off")
        }
        check(problems.isEmpty()) { "Release build is not hardened: ${problems.joinToString()}" }
        logger.lifecycle("Release build hardening OK: $s")
    }
}
tasks.named("check") { dependsOn("verifyReleaseHardening") }

// Ruling 12b: the MERGED release manifest (what actually ships, including library
// contributions) must match an explicit allowlist. The checker is a plain Kotlin class in
// src/test (MergedManifestChecker), also covered by fixture tests in MergedManifestCheckerTest;
// this task runs that same class against the release variant's merged manifest.
abstract class MergedManifestCheckArgs : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    override fun asArguments(): Iterable<String> =
        listOf(manifest.get().asFile.absolutePath, applicationId.get())
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val checkArgs = objects.newInstance<MergedManifestCheckArgs>().apply {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            applicationId.set(variant.applicationId)
        }
        val taskName = "check${variant.name.replaceFirstChar { it.uppercase() }}MergedManifest"
        val checkTask = tasks.register<JavaExec>(taskName) {
            group = "verification"
            description = "Checks permissions and exported components of the merged ${variant.name} manifest."
            // The checker lives in src/test; the debug unit-test classpath has it plus the Kotlin stdlib.
            classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
            mainClass.set("com.mbols2510.mangamanager.manifest.MergedManifestChecker")
            argumentProviders.add(checkArgs)
        }
        tasks.named("check") { dependsOn(checkTask) }
    }
}
