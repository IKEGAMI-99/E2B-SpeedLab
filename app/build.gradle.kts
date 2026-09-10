plugins {
    id("com.android.application")
}

android {
    namespace = "com.e2bspeedlab"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.e2bspeedlab"
        minSdk = 31
        targetSdk = 37
        versionCode = 16
        versionName = "0.3.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
    )
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0-alpha1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
