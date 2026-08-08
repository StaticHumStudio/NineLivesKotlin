import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.ninelivesaudio.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ninelivesaudio.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 210
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The release build, installable next to the Play build.
        //
        // Issue #64 shipped twice because nothing in the pipeline could see it:
        // debug does not run R8, and the production package is signed by Play App
        // Signing, so a locally-signed release APK can never be installed over it
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE). That left release-only behavior
        // untestable on the one real device available.
        //
        // The only differences from `release` are the applicationId, the version
        // name suffix, and the signing key. Everything R8 does is identical, which
        // is the entire point: a variant that shrinks differently proves nothing
        // about what ships.
        //
        // Do NOT set isDebuggable here. AGP silently disables optimization and
        // obfuscation for debuggable builds ("All code optimizations and
        // obfuscation are disabled for debuggable builds"), which leaves only
        // tree-shaking and turns this into a variant that cannot see the class of
        // bug it exists to catch. Stack traces deobfuscate against
        // build/outputs/mapping/releaseTest/mapping.txt, and logcat needs no
        // debuggable flag. Never submit this variant.
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8test"
            versionNameSuffix = "-r8test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// ─── R8 regression guard ──────────────────────────────────────────────────────
// See the WorkManager section of app/proguard-rules.pro for the full story. Short
// version: R8 full mode stripped the no-arg constructor that WorkManager invokes
// reflectively, every background job died before doWork() ran, and downloads were
// broken in every release build from 2.0.1 onward (issue #64).
//
// Unit tests cannot see this and debug builds cannot either, because debug does
// not minify. The only artifact that knows is R8's own usage.txt, which lists
// what R8 deleted. So the guard lives in the build.
//
// usage.txt format:
//     com.foo.Bar:          class survived, the indented members below were removed
//         public void <init>()
//     com.foo.Baz           whole class removed, no members listed
//
// Registered once per minified variant. Every variant R8 touches gets the same
// check, so `releaseTest` cannot drift into a build that verifies nothing.
val minifiedVariants = listOf("release", "releaseTest")

val r8Guards = minifiedVariants.associateWith { variant ->
    val suffix = variant.replaceFirstChar(Char::uppercase)
    tasks.register("verifyWorkManagerKeepRules$suffix") {
        group = "verification"
        description = "Fails the $variant build if R8 stripped a reflectively-invoked WorkManager constructor."

        val usageReport = layout.buildDirectory.file("outputs/mapping/$variant/usage.txt")
        outputs.upToDateWhen { false }

        // Depend on R8 by name, not just hang off it as a finalizer. Two reasons.
        // Running this task on its own now forces a fresh shrink instead of
        // reading whatever usage.txt happens to be left on disk from an older
        // build. And an AGP that renames the minify task fails here loudly rather
        // than quietly leaving the guard wired to a task that no longer exists.
        dependsOn("minify${suffix}WithR8")

        doLast {
            val report = usageReport.get().asFile
            if (!report.isFile) {
                throw GradleException(
                    "R8 usage report not found at ${report.path}. The WorkManager keep-rule guard " +
                        "could not run, so this $variant build is unverified. If the build already " +
                        "failed above, fix that first — this message is downstream noise."
                )
            }

            // Classes something else instantiates for us by name, so a missing
            // constructor is a runtime failure R8 has no way to warn about.
            val reflectivelyConstructed = Regex(
                """^(androidx\.work\..*InputMerger|com\.ninelivesaudio\.app\..*Worker)$"""
            )

            val stripped = mutableListOf<String>()
            var owner: String? = null

            report.forEachLine { line ->
                when {
                    line.isBlank() -> Unit
                    !line.first().isWhitespace() -> {
                        val name = line.removeSuffix(":")
                        if (line.endsWith(":")) {
                            owner = name
                        } else {
                            // No colon means R8 deleted the whole class. For a class
                            // we only ever reach through Class.forName that is just
                            // as fatal as losing the constructor, so it counts.
                            owner = null
                            if (reflectivelyConstructed.matches(name)) {
                                stripped += "$name → whole class removed"
                            }
                        }
                    }
                    line.contains("<init>") ->
                        owner?.takeIf(reflectivelyConstructed::matches)
                            ?.let { stripped += "$it → ${line.trim()}" }
                }
            }

            if (stripped.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("R8 stripped constructors that are only ever invoked by reflection:")
                        appendLine()
                        stripped.forEach { appendLine("    $it") }
                        appendLine()
                        appendLine("WorkManager calls getDeclaredConstructor().newInstance() on these before")
                        appendLine("doWork() ever runs, so every background job in the app will fail silently,")
                        appendLine("exactly as in issue #64. Fix the WorkManager section of")
                        appendLine("app/proguard-rules.pro rather than deleting this check.")
                    }
                )
            }
        }
    }
}

// Wired in three places per variant on purpose. The minify hook fails the build
// the moment R8 produces a bad report, before there is a shippable artifact. The
// assemble and bundle hooks are the backstop, so a future AGP renaming the minify
// task turns the guard into a redundant run instead of a silent no-op.
val r8GuardTriggers = minifiedVariants.flatMap { variant ->
    val suffix = variant.replaceFirstChar(Char::uppercase)
    listOf("minify${suffix}WithR8", "assemble$suffix", "bundle$suffix").map { it to variant }
}.toMap()

tasks.configureEach {
    r8GuardTriggers[name]?.let { variant -> finalizedBy(r8Guards.getValue(variant)) }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // ProcessLifecycleOwner: Billing must re-query on every foreground, because an
    // out-of-app promo redemption produces no purchase-update callback here.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room (Database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + OkHttp (Network)
    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    // Coil (Image Loading)
    implementation(libs.coil.compose)

    // Media3 (Audio Playback)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // Security (Encrypted Storage)
    implementation(libs.security.crypto)

    // DocumentFile (SAF tree traversal for local library scanner)
    implementation(libs.androidx.documentfile)

    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager (background downloads, foreground-service notification)
    implementation(libs.androidx.work.runtime.ktx)

    // Play Billing (one-time unlock)
    implementation(libs.billing.ktx)

    // Play In-App Review
    implementation(libs.play.review.ktx)

    // ACRA (Crash Reporting)
    implementation("ch.acra:acra-mail:5.11.4")
    implementation("ch.acra:acra-dialog:5.11.4")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
