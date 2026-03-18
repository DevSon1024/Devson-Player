plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.devson.devsonplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devson.devsonplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-ffast-math",
                    "-O2",
                    "-DANDROID",
                    "-DNDEBUG"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DFFMPEG_JNI_DIR=${layout.buildDirectory.get().asFile.absolutePath}/ffmpeg-jni"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    ndkVersion = "27.0.12077973"

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            // Keep only the ABIs we support; deduplicate .so files from multiple AARs
            pickFirsts += listOf(
                "lib/arm64-v8a/*.so",
                "lib/x86_64/*.so"
            )
        }
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // A configuration strictly for extracting FFmpeg JNI libs
    val ffmpegConfig by configurations.creating

    implementation("com.google.oboe:oboe:1.8.0")

    // Media3 / ExoPlayer (hardware decode path)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.extractor)

    // Use the 16KB page-aligned, full-gpl version for maximum codec & Android 15 support
    ffmpegConfig("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-full-gpl-16kb:6.1.4")
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-full-gpl-16kb:6.1.4")

    // Coil (video thumbnails)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

//  Extract FFmpeg .so files before CMake runs 
val extractFFmpegJni by tasks.registering(Copy::class) {
    val ffmpegConfig = configurations.getByName("ffmpegConfig")
    dependsOn(ffmpegConfig)
    
    from(provider {
        ffmpegConfig.files
            .filter { it.name.endsWith(".aar") }
            .map { file -> zipTree(file).matching { include("jni/**") } }
    })
    into(layout.buildDirectory.dir("ffmpeg-jni").get().asFile)
    eachFile {
        path = path.replaceFirst("jni/", "")
    }
    includeEmptyDirs = false
}

// Ensure the extraction runs before any CMake configure or build tasks
tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        name.startsWith("generateJsonModel") ||
        name == "preBuild") {
        dependsOn(extractFFmpegJni)
    }
}