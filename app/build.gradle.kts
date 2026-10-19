plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "ab.inque.visiontechai"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "ab.inque.prismai"
        minSdk = 27
        targetSdk = 36
        versionCode = 14
        versionName = "0.4.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        signingConfig = signingConfigs.getByName("debug")

        externalNativeBuild {
            cmake {
                cppFlags += "-Wall"
                arguments += "-DANDROID_STL=c++_shared"
                // 16 KB page alignment for Android 15+ (NDK r27+; harmless on older NDK)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
        ndk {
            // Play Console (API 36): ship ARM only. Debug adds x86_64 for emulator testing.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/robotics/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/robotics/java")
        }
        getByName("test") {
            java.srcDirs("src/test/java", "src/robotics/test/java")
        }
    }

    lint {
        disable.add("NullSafeMutableLiveData")
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("x86_64", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("tflite", "data", "lite")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    // AGP 8.5.1+: use uncompressed native libs so they can be 16 KB zip-aligned (required for Play).
    // Do not use useLegacyPackaging = true; that compresses .so and is only for AGP < 8.5.1.
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.9.0"))
    implementation("com.google.firebase:firebase-analytics")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // LiteRT 1.4.2 — 16 KB-aligned libtensorflowlite_jni (replaces tensorflow-lite 2.16)
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-support:1.4.2")
    implementation("com.google.ai.edge.litert:litert-metadata:1.4.2")
    
    // ML Kit: text recognition (Latin) and on-device translation
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // Play requires Billing Library 8.0.0+ on the uploaded AAB.
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

/** Overlapping / formatter / sample tests kept locally; GitHub runs `-PciCoreTests=true`. */
val ciCoreUnitTestExcludes = listOf(
    "ab.inque.visiontechai.ExampleUnitTest",
    "ab.inque.visiontechai.QuaternionMathMultiplyTest",
    "ab.inque.visiontechai.VisionModeTest",
    "ab.inque.visiontechai.VisionModeTransitionsTest",
    "ab.inque.visiontechai.SphereMotionDepthTest",
    "ab.inque.visiontechai.SphereMotionEstimatorTest",
    "ab.inque.visiontechai.SphereCaptureThrottlerTest",
    "ab.inque.visiontechai.SpherePreviewIntegrationTest",
    "ab.inque.visiontechai.PipelineResultDispatcherSphereTest",
    "ab.inque.visiontechai.controller.SphereSessionControllerPreviewTest",
    "ab.inque.visiontechai.SphereRefineOverlapMetricsTest",
    "ab.inque.visiontechai.CameraFovCalculatorTest",
    "ab.inque.visiontechai.SphereFovHelperTest",
    "ab.inque.visiontechai.ProcessorParamsTest",
    "ab.inque.visiontechai.PipelineResultTest",
    "ab.inque.visiontechai.SphereCoverageFormatterTest",
    "ab.inque.visiontechai.TranslationExportFormatterTest",
    "ab.inque.visiontechai.HdScaleMapperTest",
    "ab.inque.visiontechai.SphereHoleOverlayTest",
    "ab.inque.visiontechai.ObjectDetectionOverlayTest",
    "ab.inque.visiontechai.SphereStitchTelemetryTest",
    "ab.inque.visiontechai.SphereXmpMetadataTest",
    "ab.inque.visiontechai.TextLineClustererGoldenTest",
    "ab.inque.visiontechai.FrameProcessorAdaptersTest",
    "ab.inque.visiontechai.VisionOverlayHostTest",
    "ab.inque.visiontechai.TranslationLanguagesTest",
    "ab.inque.visiontechai.SphereAlignmentFallbackTest",
    "ab.inque.visiontechai.SphereStitchFramePrepTest",
    "ab.inque.visiontechai.TileGainMapTest",
    "ab.inque.visiontechai.SavedMediaTrackerTest",
    "ab.inque.visiontechai.SpherePoseRefinementTest",
    "ab.inque.visiontechai.ImageResampleUtilsTest",
    "ab.inque.visiontechai.LensDistortionStoreTest"
)

tasks.withType<Test>().configureEach {
    if (project.findProperty("ciCoreTests") == "true") {
        filter {
            ciCoreUnitTestExcludes.forEach { excludeTestsMatching(it) }
            isFailOnNoMatchingTests = true
        }
    }
}
