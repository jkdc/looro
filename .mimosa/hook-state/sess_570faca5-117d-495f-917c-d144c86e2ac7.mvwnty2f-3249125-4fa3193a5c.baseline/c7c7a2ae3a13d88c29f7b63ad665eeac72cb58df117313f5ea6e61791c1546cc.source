import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val appLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Single source of brand identity: branding.properties at the repo root.
val branding = Properties().apply {
    rootProject.file("../../branding.properties").inputStream().use { load(it) }
}
fun appConfiguration(name: String): String = providers.gradleProperty(name).orNull ?: appLocal.getProperty(name, "")
fun buildString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\""
val appMinutePurchases = appConfiguration("app.minutePurchasesEnabled").ifBlank { "false" }
val appPurchaseChannel = appConfiguration("app.purchaseChannel").ifBlank { "play" }
require(appPurchaseChannel in listOf("play", "stripe")) { "app.purchaseChannel must be play or stripe" }
val appMinuteEnvironment = appConfiguration("app.minutePurchaseEnvironment").ifBlank { "test" }
require(appMinutePurchases in listOf("false", "true")) { "app.minutePurchasesEnabled must be false or true" }
require(appMinuteEnvironment in listOf("test", "live")) { "app.minutePurchaseEnvironment must be test or live" }

// Conversation engine selection: -Papp.conversationEngine > local.properties > APP_CONVERSATION_ENGINE env > openai.
val appConversationEngine = (
    providers.gradleProperty("app.conversationEngine").orNull
        ?: appLocal.getProperty("app.conversationEngine")
        ?: System.getenv("APP_CONVERSATION_ENGINE")
        ?: "openai"
    ).trim().lowercase()
val validConversationEngines = listOf("openai", "deepseek", "glm", "glm-4.5")
require(appConversationEngine in validConversationEngines) {
    "app.conversationEngine must be one of $validConversationEngines (got '$appConversationEngine')"
}
android {
    namespace = branding.getProperty("ANDROID_NAMESPACE")
    compileSdk = 36
    defaultConfig {
        applicationId = branding.getProperty("ANDROID_APPLICATION_ID")
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MANAGED_API_ORIGIN", buildString(appConfiguration("app.apiOrigin")))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildString(appConfiguration("app.googleServerClientID")))
        buildConfigField("boolean", "MINUTE_PURCHASES_ENABLED", appMinutePurchases)
        buildConfigField("String", "PURCHASE_CHANNEL", buildString(appPurchaseChannel))
        buildConfigField("String", "MINUTE_PURCHASE_ENVIRONMENT", buildString(appMinuteEnvironment))
        buildConfigField("String", "CONVERSATION_ENGINE", buildString(appConversationEngine))
    }
    // A personal installation can preserve its local signing identity across SDK resets.
    // This file is ignored by Git; clean checkouts use Android's standard debug key.
    val personalDebugKey = rootProject.file(".signing/debug.keystore")
    if (personalDebugKey.exists()) signingConfigs.getByName("debug").storeFile = personalDebugKey
    // Interface tests install as a separate app so they never read or change a learner's data.
    buildTypes {
        create("uiTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += "debug"
            buildConfigField("String", "MANAGED_API_ORIGIN", "\"\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"\"")
            buildConfigField("boolean", "MINUTE_PURCHASES_ENABLED", "false")
            buildConfigField("String", "MINUTE_PURCHASE_ENVIRONMENT", "\"test\"")
        }
    }
    testBuildType = "uiTest"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("../../shared/fixtures/cross-platform"))
        .withPropertyName("crossPlatformFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(project(":core:engine-local"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real SQLite for the JVM-side database tests (AndroidSqlDatabase runs on device).
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "uiTestImplementation"("androidx.compose.ui:ui-test-manifest")
}
