import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

// Load local.properties for dev API URL (ngrok). Not committed; see local.properties.example.
val localPropertiesFile = rootProject.file("local.properties")
val devApiBaseUrl = if (localPropertiesFile.exists()) {
    Properties().apply {
        load(localPropertiesFile.inputStream())
    }.getProperty("pursue.api.base.url", "https://api.getpursue.app/api")
} else {
    "https://api.getpursue.app/api"
}

android {
    namespace = "app.getpursue"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.getpursue"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$devApiBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.getpursue.app/api\"")
            isMinifyEnabled = false
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
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // In CI only: exclude E2E tests from testDebugUnitTest (they need a running backend).
            // Locally the exclude is off so E2E can be run from Android Studio or via testE2e.
            all {
                if (System.getenv("CI") == "true") {
                    it.exclude("**/*E2ETest.class", "**/*E2ETest\$*.class")
                }
            }
        }
    }
    
    // Add E2E sources to test source set (workaround for Android Gradle Plugin not supporting custom source sets)
    sourceSets {
        getByName("test") {
            java.srcDir("src/e2e/kotlin")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.shimmer)
    
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
    
    // Google Sign-In
    implementation(libs.googleSignIn)
    
    // Image loading
    implementation(libs.glide)
    implementation(libs.glideOkHttp)
    
    // Image cropping
    implementation(libs.imageCropper)
    implementation(libs.zxing)
    // QR scanning (JoinGroupBottomSheet); exclude transitive zxing core to use our 3.5.3
    implementation(libs.zxing.android.embedded) {
        exclude(group = "com.google.zxing", module = "core")
    }

    // Google Play Billing (subscriptions)
    implementation(libs.billing.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.fragment.testing)
    
    // E2E test dependencies - add to testImplementation since E2E sources are in test source set
    // These are needed for E2E tests but won't interfere with unit tests
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttpLogging)
    testImplementation(libs.gson)
    testImplementation(libs.truth)
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    // E2E test dependencies - create a custom configuration for potential future use
    val e2eImplementation by configurations.creating {
        extendsFrom(configurations.getByName("testImplementation"))
        isCanBeResolved = true  // Make it resolvable for use in testE2e task
    }
    
    // Add E2E-specific dependencies to e2eImplementation as well (for consistency)
    e2eImplementation(libs.junit)
    e2eImplementation(libs.kotlinx.coroutines.test)
    e2eImplementation(libs.okhttp)
    e2eImplementation(libs.okhttpLogging)
    e2eImplementation(libs.gson)
    e2eImplementation(libs.truth)
}

// Custom Gradle task for E2E tests
// Note: E2E sources are added to test source set, so they compile with test sources
// Use afterEvaluate to ensure everything is configured before accessing
afterEvaluate {
    tasks.register<Test>("testE2e") {
        description = "Runs E2E tests against local dev server (localhost:3000)"
        group = "verification"
        
        // Depend on test compilation to ensure E2E classes are compiled
        dependsOn("compileDebugUnitTestKotlin", "compileDebugKotlin")
        
        // Use the test classes directory where compiled test classes are located
        // E2E sources compile with test sources, so they're in the same output directory
        testClassesDirs = files(
            project.layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes"),
            project.layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
        )
        
        // Build classpath: need main classes, test classes, and all dependencies
        // The main classes are required because E2E tests use classes from the main source set (e.g., ApiException)
        // Access the testDebugUnitTest task and copy its classpath configuration
        val standardTestTask = tasks.findByName("testDebugUnitTest")
        
        if (standardTestTask is Test) {
            // Use the exact same classpath as the standard test task
            // This ensures all dependencies and compiled classes are included
            classpath = standardTestTask.classpath
        } else {
            // Fallback: build classpath manually
            // Get the actual File objects from the build directories
            val buildDir = project.layout.buildDirectory.get().asFile
            val mainClassesDir = buildDir.resolve("intermediates/javac/debug/classes")
            val mainKotlinClassesDir = buildDir.resolve("tmp/kotlin-classes/debug")
            val testClassesDir = buildDir.resolve("intermediates/javac/debugUnitTest/classes")
            val testKotlinClassesDir = buildDir.resolve("tmp/kotlin-classes/debugUnitTest")
            
            // Use resolvable e2eImplementation configuration (includes all test dependencies)
            // Plus add the compiled main and test classes as file trees
            classpath = configurations.getByName("e2eImplementation") +
                        files(mainClassesDir) +
                        files(mainKotlinClassesDir) +
                        files(testClassesDir) +
                        files(testKotlinClassesDir)
        }
        
        // Include only E2E test classes (those ending in E2ETest)
        include("**/*E2ETest.class", "**/*E2ETest\$*.class")
        
        // Skip E2E tests in CI
        if (System.getenv("CI") == "true") {
            enabled = false
            println("⚠️  E2E tests skipped in CI environment")
        }
        
        // Show test output
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
        
        doFirst {
            println("=".repeat(60))
            println("⚠️  PREREQUISITE: Backend server must be running!")
            println("   Run: cd backend && npm run dev")
            println("   Server should be at: http://localhost:3000")
            println("=".repeat(60))
        }
        
        doLast {
            println("=".repeat(60))
            println("✅ E2E tests completed")
            println("=".repeat(60))
        }
    }
}