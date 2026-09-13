plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ergouf.gecis"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.ergouf.gecis"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.2.7"

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

    val releaseStoreFile = file(project.findProperty("storeFile") as? String ?: "${rootDir}/release.keystore")
    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = project.findProperty("storePassword") as? String ?: "gecis123"
            keyAlias = project.findProperty("keyAlias") as? String ?: "gecis"
            keyPassword = project.findProperty("keyPassword") as? String ?: "gecis123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (releaseStoreFile.isFile) {
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
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
}
