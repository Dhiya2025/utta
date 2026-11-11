plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.example.miniwalkie"
    compileSdk = 35
    defaultConfig { applicationId = "com.example.miniwalkie"; minSdk = 23; targetSdk = 35; versionCode = 3; versionName = "1.2" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
