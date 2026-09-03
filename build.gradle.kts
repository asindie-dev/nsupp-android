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
    // Maven Central yayını — üçüncü taraf yayın eklentisi KULLANILMADI: Gradle'ın kendi
    // `maven-publish`i yerel bir dizine standart Maven yerleşimi (checksum'lar dahil) üretir,
    // Central Portal da zaten o yerleşimin ZIP'ini istiyor. Bir eklenti daha eklemek, yayın
    // hattını bakımı bize ait olmayan bir sürüm çizelgesine bağlardı.
    `maven-publish`
    signing
}

// ── Maven Central koordinatları ──────────────────────────────────────────────────────────────
// groupId olarak `io.github.asindie-dev` DEĞİL kendi alan adımız: diğer bütün ekosistemlerde
// ad `nsupp` (npm `@nsupp/*`, Packagist `nsupp/rest`, PyPI `nsupp-rest`, Go `nsupp.com/rest-go`).
// Doğrulaması `nsupp.com` üzerinde tek bir DNS TXT kaydıdır ve alan adı bizim.
group = "com.nsupp"
version = "0.1.0"

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

    // 🔴 KAYNAK ve JAVADOC JAR'I ZORUNLUDUR: Central Portal her `<artifactId>-<version>.jar`
    //    için `-sources.jar` ve `-javadoc.jar` ister; eksikse yükleme DOĞRULAMADA reddedilir.
    //    AGP bunları kendi üretir — elle `Jar` görevi yazmak varyant çıktısını ıskalardı.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
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


// ── Yayın ────────────────────────────────────────────────────────────────────────────────────
publishing {
    publications {
        create<MavenPublication>("release") {
            // `afterEvaluate` ZORUNLU: Android bileşeni (`components["release"]`) yapılandırma
            // aşamasının SONUNDA oluşur; erken referans `SoftwareComponent with name 'release'
            // not found` ile düşer.
            afterEvaluate { from(components["release"]) }
            artifactId = "nsupp-android"
            pom {
                // Aşağıdaki alanların HEPSİ Central Portal'da ZORUNLUDUR (name · description ·
                // url · license · developers · scm). Biri eksikse yükleme reddedilir.
                name.set("nsupp Android SDK")
                description.set(
                    "Official Android SDK for nsupp live chat and support — embeddable chat " +
                        "surface, FCM push bridge, zero third-party network/JSON dependencies.",
                )
                url.set("https://github.com/asindie-dev/nsupp-android")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("asindie")
                        name.set("Asindie, Inc.")
                        url.set("https://nsupp.com")
                    }
                }
                scm {
                    // 🔴 AYNA REPO gösterilir, monorepo DEĞİL: monorepo PRIVATE'tır ve Maven
                    //    Central'daki bir SCM bağlantısının herkesçe açılabilir olması gerekir.
                    url.set("https://github.com/asindie-dev/nsupp-android")
                    connection.set("scm:git:https://github.com/asindie-dev/nsupp-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/asindie-dev/nsupp-android.git")
                }
            }
        }
    }
    repositories {
        // Yerel dizin deposu: Central Portal API'si standart Maven YERLEŞİMİNİN zip'ini istiyor.
        // Gradle buraya yayınlarken `.md5`/`.sha1` checksum'larını da yazar (bunlar da zorunlu).
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// ── İmzalama ─────────────────────────────────────────────────────────────────────────────────
// 🔴 HER DOSYA İÇİN `.asc` ZORUNLUDUR (Central Portal kuralı) — jar, sources, javadoc, pom, module.
// 🔴 ANAHTAR BELLEKTEN OKUNUR, DİSKTEN DEĞİL: CI'da keyring dosyası bırakmak özel anahtarı
//    çalışma alanına yazmak demektir. `useInMemoryPgpKeys` ortam değişkeninden alır.
// 🔴 ANAHTAR YOKSA İMZALAMA KAPALI ve bu BİLİNÇLİ: geliştirici makinesinde `assembleRelease`
//    çalışmaya devam etsin. Ama yayın işi, yüklemeden ÖNCE `.asc` dosyalarının VARLIĞINI ayrıca
//    ölçer — yani imzasız bir paket sessizce yayına gidemez.
signing {
    val anahtar = providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY").orNull
    val parola = providers.environmentVariable("MAVEN_GPG_PASSPHRASE").orNull
    isRequired = anahtar != null
    if (anahtar != null) {
        useInMemoryPgpKeys(anahtar, parola)
        afterEvaluate { sign(publishing.publications["release"]) }
    }
}
