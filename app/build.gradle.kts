plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFilePath = providers.gradleProperty("GECIS_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("GECIS_SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("GECIS_SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("GECIS_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured =
    !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.ergouf.gecis"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.ergouf.gecis"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.8"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libgecis_*.so")
        }
    }
}

val verifyReleaseSigningConfig = tasks.register("verifyReleaseSigningConfig") {
    doLast {
        val missing = buildList {
            if (releaseStoreFilePath.isNullOrBlank()) add("GECIS_SIGNING_STORE_FILE")
            if (releaseStorePassword.isNullOrBlank()) add("GECIS_SIGNING_STORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("GECIS_SIGNING_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("GECIS_SIGNING_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is required. Missing Gradle properties: ${missing.joinToString(", ")}. " +
                    "Set them in ~/.gradle/gradle.properties or via ORG_GRADLE_PROJECT_* environment variables.",
            )
        }

        val keystore = rootProject.file(releaseStoreFilePath!!)
        if (!keystore.isFile || keystore.length() == 0L) {
            throw GradleException("Release signing keystore does not exist or is empty: ${keystore.absolutePath}")
        }
    }
}

val verifyGeneratedPayloads = tasks.register("verifyGeneratedPayloads") {
    doLast {
        val requiredFiles = listOf(
            "src/main/jniLibs/arm64-v8a/libgecis_agy.so",
            "src/main/jniLibs/arm64-v8a/libgecis_ld.so",
            "src/main/assets/runtime/cacert.pem",
            "src/main/assets/vendor/katex/katex.min.css",
            "src/main/assets/vendor/katex/katex.min.js",
            "src/main/assets/vendor/katex/auto-render.min.js",
            "src/main/assets/vendor/marked/marked.umd.js",
            "src/main/assets/vendor/dompurify/purify.min.js",
        ).map(::file)

        val missing = requiredFiles.filterNot { it.isFile && it.length() > 0L }
        val fontDir = file("src/main/assets/vendor/katex/fonts")
        val hasKatexFonts = fontDir.isDirectory &&
            (fontDir.listFiles()?.any { it.isFile && it.extension == "woff2" && it.length() > 0L } == true)

        if (missing.isNotEmpty() || !hasKatexFonts) {
            val details = buildString {
                missing.forEach { append("\n - ").append(it.relativeTo(projectDir)) }
                if (!hasKatexFonts) append("\n - src/main/assets/vendor/katex/fonts/*.woff2")
            }
            throw GradleException(
                "Generated Gecis payloads are missing:$details\n" +
                    "Run: bash web/stage-assets.sh && bash native/probe-runtime.sh && bash native/stage-runtime.sh",
            )
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(verifyGeneratedPayloads)
    if (name == "preReleaseBuild") dependsOn(verifyReleaseSigningConfig)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
}
