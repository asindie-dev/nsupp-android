// nsupp Android SDK — kütüphane modülü.
//
// BAĞIMLILIK POLİTİKASI: bir SDK'nın taşıdığı her bağımlılık satıcının uygulamasına dayatılır.
// Bu yüzden ağ/JSON için üçüncü taraf YOK (HttpURLConnection + elle okuma). Compose ve Firebase
// `compileOnly` DEĞİL çünkü kullanıcı arayüzü ve FCM köprüsü SDK'nın vaat ettiği yüzeyin parçası —
// ama satıcı yalnız çekirdeği isterse `nsupp-core` kaynak kümesi Android'e hiç dokunmaz.
// Sürümler BURADA sabit: bu proje bağımsız derleniyor (settings.gradle.kts). React Native modülü
// çekirdeğin KAYNAKLARINI kendi eklenti sürümleriyle derler, bu blok oraya karışmaz.
plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    // Kotlin 2.x: Compose derleyicisi ARTIK eklenti; `composeOptions` yolu kaldırıldı.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.nsupp.sdk"
    compileSdk = 35

    defaultConfig {
        // 24 = Android 7.0. Play Console'un bildirdiği cihaz tabanının ~%99'u; daha düşüğe inmek
        // TLS 1.2 ve modern Compose için sorun çıkarır.
        minSdk = 24
        // WebView kabuğunun güvenlik sınırı (köprünün bağlandığı ORIGIN, alt çerçevenin elenmesi,
        // POST kaçışının geri alınması, `reset()`in depoyu silmesi) yalnız GERÇEK WebView'da
        // gözlenebilir; o testler `src/androidTest` altında cihazda/emülatörde koşar.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    // İSTİSNA DEĞİL, ZORUNLULUK: sohbet WebView'ında kimliği (uygulama anahtarı + ziyaretçi jetonu)
    // ORIGIN'e bağlayabilen tek dokümante yol bu kütüphanededir (`addDocumentStartJavaScript` +
    // `addWebMessageListener`). Platformun kendi `addJavascriptInterface`i köprüyü WebView'a bağlar,
    // origin'e değil — sayfanın TÜM çerçevelerine açılır.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    // FCM köprüsü. Satıcı kendi servisini kullanacaksa bu sınıfı hiç referans etmez.
    implementation("com.google.firebase:firebase-messaging:24.0.1")

    // JVM birim testleri: Android'e HİÇ dokunmayan çekirdek (NsuppApi/NsuppSession/Json) ve saf
    // sunum ölçüleri (NsuppOlculer). Emülatör gerektirmezler.
    testImplementation(kotlin("test"))
    // Enstrümanlı testler: `androidx.test.ext:junit` AndroidJUnit4 koşucusunu, `androidx.test:runner`
    // da manifest'e yazılan AndroidJUnitRunner'ı ve `InstrumentationRegistry`yi getirir. Bu iki
    // bağımlılık YALNIZ test APK'sına girer — AAR'a ve satıcının uygulamasına geçmez.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
