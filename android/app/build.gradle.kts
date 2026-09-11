plugins {
    id("com.android.application")
}

android {
    namespace = "com.yestermester.simpsons"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.yestermester.simpsons"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // arm64-v8a only: the recompiled guest code, NEON paths and the
        // Vulkan backend are arm64-first; 32-bit devices are not supported
        // (the guest memory map needs a 64-bit host).
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DREX_PROJECT_ROOT=${project.projectDir.parentFile.parentFile.absolutePath}",
                )
                cppFlags += listOf("-std=c++23")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign release APKs with the debug key: these builds are shared
            // directly (CI artifacts / scripts/build-android.sh --release),
            // not through a store, and an unsigned APK cannot be installed at
            // all. Players who want their own key can replace it locally.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Keep symbols useful for tombstone triage.
            isJniDebuggable = true
        }
    }

    packaging {
        // Real extracted libs: the runtime resolves symbols between
        // libmain.so and librexruntime.so through nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
