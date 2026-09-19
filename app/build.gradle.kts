plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.netanel.roadwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netanel.roadwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    testImplementation("junit:junit:4.13.2")
}


val aiModelUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite"
val aiModelFile = layout.projectDirectory.file("src/main/assets/efficientdet_lite2_int8.tflite")

val downloadAiModel = tasks.register("downloadAiModel") {
    outputs.file(aiModelFile)
    doLast {
        val target = aiModelFile.asFile
        if (!target.exists() || target.length() < 2_000_000L) {
            target.parentFile.mkdirs()
            val temp = File(target.parentFile, target.name + ".download")
            if (temp.exists()) temp.delete()
            val connection = java.net.URI(aiModelUrl).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            require(temp.length() >= 2_000_000L) { "Downloaded AI model is unexpectedly small" }
            if (target.exists()) target.delete()
            check(temp.renameTo(target) || run {
                temp.copyTo(target, overwrite = true)
                temp.delete()
                true
            })
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadAiModel)
}
