plugins {
    alias(libs.plugins.android.application)
    // 1. Properly add the Chaquopy plugin inside the plugins block
    id("com.chaquo.python")
}

android {
    namespace = "xyz.sunkastudios.localtube"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "xyz.sunkastudios.localtube"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("libnode/bin")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// 2. Clean, dedicated top-level block instead of configure<...>
chaquopy {
    defaultConfig {
        buildPython("python3")
        version = "3.14"

        pip {
            // Keep options at the top of the single block
            options("--no-deps")

            // Main package
            install("anipy-api")

            // Manually satisfied requirements (prevents dependency tracing bugs)
            install("pycountry")
            install("requests")
            install("urllib3")
            install("certifi")
            install("typing_extensions")
            install("idna")
            install("soupsieve")
            install("beautifulsoup4")
            install("dataclasses-json")
            install("yarl")
            install("m3u8")
            install("thefuzz")
        }
    }
}


dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
}
