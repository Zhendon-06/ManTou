import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.navigation.safe.args.kotlin)
    alias(libs.plugins.ksp)
}

val includeLinuxRuntime = providers.gradleProperty("withLinuxRuntime").orNull == "true"
val linuxAssets = layout.buildDirectory.dir("generated/linuxRuntime/assets")

if (includeLinuxRuntime) {
    val prepareLinuxRuntime = tasks.register<Exec>("prepareLinuxRuntime") {
        val defaultPython = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val output = linuxAssets.get().dir("linux-runtime/bin")
        commandLine(
            providers.gradleProperty("linuxPython").getOrElse(defaultPython),
            "scripts/build_linux_runtime.py",
            output.asFile.absolutePath,
            providers.gradleProperty("linuxNode").getOrElse("node")
        )
        workingDir(rootProject.projectDir)
        inputs.file(rootProject.file("scripts/build_linux_runtime.py"))
        inputs.files(listOf("init", "runner.py", "snapshot.mjs", "packages.sha256")
            .map { rootProject.file("scripts/linux-guest/$it") })
        outputs.dir(output)
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareLinuxRuntime) }
}

android {
    if (includeLinuxRuntime) {
        sourceSets.getByName("main").assets.srcDir(linuxAssets)
    }
    namespace = "com.hfad.mantou"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hfad.mantou"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    ksp(project(":tool-ksp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.backdrop)
    constraints {
        implementation(libs.androidx.graphics.path) {
            because("Use the updated Graphics Path native library for 16 KB page size compatibility")
        }
    }
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.cardview)
    implementation(libs.glide)
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tasklist)
    implementation(libs.commonmark.tables)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Local embedding inference
    implementation(libs.onnxruntime.android)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
