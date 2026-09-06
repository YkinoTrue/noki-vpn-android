import java.util.Properties
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.google.gms.google-services")

layout.buildDirectory.set(file("build-compose-2026"))

val releaseSigningPropertiesFile = rootProject.file("sha-256 key/release-signing.key")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val requiredReleaseSigningProperties = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)
val defaultNokiApiBaseUrl = "https://api.noki.ykino.tech"
val nokiApiBaseUrl = providers.gradleProperty("noki.apiBaseUrl")
    .orElse(defaultNokiApiBaseUrl)
    .get()
    .trimEnd('/')
val nokiBackendProbeHost = providers.gradleProperty("noki.backendProbeHost")
    .orElse(URI(nokiApiBaseUrl).host ?: "api.noki.ykino.tech")
    .get()
val nokiBackendProbeHealthUrl = providers.gradleProperty("noki.backendProbeHealthUrl")
    .orElse("$nokiApiBaseUrl/health")
    .get()
val googleWebClientId = providers.gradleProperty("noki.googleWebClientId")
    .orElse("")
    .get()
val libv2rayAarFile = layout.projectDirectory.file("libs/libv2ray.aar")
val libv2rayAarSha256 = "CF1D829174C12CD4781725DDDF30347A9231014F18684619208521EBD5B020D4"
val expectedReleaseVersionCode = 215
val expectedReleaseVersionName = "0.9.195"

fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "com.noki.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noki.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = expectedReleaseVersionCode
        versionName = expectedReleaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "NOKI_API_BASE_URL", nokiApiBaseUrl.toBuildConfigString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.toBuildConfigString())
        buildConfigField("String", "NOKI_BACKEND_PROBE_HOST", nokiBackendProbeHost.toBuildConfigString())
        buildConfigField("boolean", "DIAGNOSTIC_LOGGING", "false")
        buildConfigField(
            "String",
            "TELEGRAM_LOGIN_REDIRECT_URI",
            "https://app3992881250-login.tg.dev/tglogin".toBuildConfigString(),
        )
        buildConfigField(
            "String",
            "NOKI_BACKEND_PROBE_HEALTH_URL",
            nokiBackendProbeHealthUrl.toBuildConfigString(),
        )
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("releaseLocal") {
            val storeFilePath = releaseSigningProperties.getProperty("storeFile").orEmpty()
            if (storeFilePath.isNotBlank()) {
                storeFile = rootProject.file(storeFilePath)
            }
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        create("diagnostic") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            versionNameSuffix = "-diagnostic"
            buildConfigField("boolean", "DIAGNOSTIC_LOGGING", "true")
            if (releaseSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseLocal")
            }
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseLocal")
            }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("validateLocalReleaseSigning") {
    doLast {
        require(releaseSigningPropertiesFile.exists()) {
            "Release signing properties must exist at sha-256 key/release-signing.key"
        }
        val missing = requiredReleaseSigningProperties.filter { key ->
            releaseSigningProperties.getProperty(key).isNullOrBlank()
        }
        require(missing.isEmpty()) {
            "Release signing properties are missing: ${missing.joinToString(", ")}"
        }
        val storeFilePath = releaseSigningProperties.getProperty("storeFile").orEmpty()
        require(rootProject.file(storeFilePath).exists()) {
            "Release keystore must exist at $storeFilePath"
        }
    }
}

tasks.register("verifyLibv2rayAar") {
    inputs.file(libv2rayAarFile)
    doLast {
        val aar = libv2rayAarFile.asFile
        require(aar.exists()) {
            "libv2ray.aar must exist at ${aar.relativeTo(projectDir)}"
        }
        val actual = sha256Hex(aar)
        require(actual.equals(libv2rayAarSha256, ignoreCase = true)) {
            "libv2ray.aar checksum mismatch: expected $libv2rayAarSha256, got $actual"
        }
    }
}

tasks.register("verifyReleaseVersion") {
    doLast {
        require(android.defaultConfig.versionCode == expectedReleaseVersionCode) {
            "Expected release versionCode $expectedReleaseVersionCode, got ${android.defaultConfig.versionCode}"
        }
        require(android.defaultConfig.versionName == expectedReleaseVersionName) {
            "Expected release versionName $expectedReleaseVersionName, got ${android.defaultConfig.versionName}"
        }
    }
}

tasks.register<Copy>("copyNamedReleaseApks") {
    dependsOn("packageRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    exclude("app-x86-release.apk")
    into(layout.buildDirectory.dir("outputs/apk/release-named"))
    doFirst {
        delete(layout.buildDirectory.dir("outputs/apk/release-named"))
    }
    rename { fileName ->
        val abi = when {
            fileName.contains("arm64-v8a") -> "arm64-v8a"
            fileName.contains("armeabi-v7a") -> "armeabi-v7a"
            fileName.contains("x86_64") -> "x86_64"
            fileName.contains("universal") -> "universal"
            else -> null
        }
        abi?.let { "Noki Vpn-${android.defaultConfig.versionName}-$it.apk" } ?: fileName
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("verifyLibv2rayAar")
    dependsOn("verifyReleaseVersion")
    dependsOn("validateLocalReleaseSigning")
    finalizedBy("copyNamedReleaseApks")
}

tasks.register<Copy>("copyDiagnosticApks") {
    dependsOn("packageDiagnostic")
    from(layout.buildDirectory.dir("outputs/apk/diagnostic"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/diagnostic-package"))
    doFirst {
        delete(layout.buildDirectory.dir("outputs/diagnostic-package"))
    }
    rename { fileName ->
        val abi = when {
            fileName.contains("arm64-v8a") -> "arm64-v8a"
            fileName.contains("armeabi-v7a") -> "armeabi-v7a"
            fileName.contains("x86_64") -> "x86_64"
            fileName.contains("universal") -> "universal"
            else -> "unknown"
        }
        "Noki-Vpn-${android.defaultConfig.versionName}-diagnostic-$abi.apk"
    }
}

tasks.matching { it.name == "assembleDiagnostic" }.configureEach {
    finalizedBy("copyDiagnosticApks")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("verifyLibv2rayAar")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.14.0")

    implementation(files("libs/libv2ray.aar"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    implementation(composeBom)
    implementation(firebaseBom)
    debugImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("androidx.camera:camera-core:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")

    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
}
