plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing comes from the environment, never from a file in the repo.
 *
 * CI sets these from repository secrets; on a normal machine they are absent and
 * `assembleRelease` simply produces an unsigned APK. That way nobody needs a keystore just to
 * compile the project, and the keystore never has to exist anywhere in version control.
 */
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasReleaseSigning: Boolean = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.mazhar.deliberate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mazhar.deliberate"
        // 26 rather than 24: adaptive launcher icons land at 26, which lets the whole app
        // ship with zero binary assets. Android 8.0+ is effectively every device in use.
        minSdk = 26
        targetSdk = 35
        // The release workflow derives these from the git tag; the fallbacks are for local builds.
        versionCode = (System.getenv("VERSION_CODE") ?: "4").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.4-phase4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // EncryptedSharedPreferences. 1.1.0-alpha06 is the version whose MasterKey.Builder /
    // EncryptedSharedPreferences.create(context, ...) signatures PinManager uses; the 1.0.0
    // stable line has a different, deprecated API shape.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
