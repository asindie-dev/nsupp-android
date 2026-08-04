// nsupp Android SDK — bağımsız Gradle projesi.
//
// NEDEN VAR: kütüphanenin `build.gradle.kts`'i baştan beri duruyordu ama projeyi AÇAN dosya
// (settings) yoktu; bu yüzden SDK hiçbir zaman kendi başına derlenemedi ve testleri elle yazılmış
// Android saplamalarıyla (`tools/Shim*.kt`) JVM'de koşturulmaya çalışıldı. O yol iki kez ısırdı:
// saplamaya karşı derlenen kod YEŞİL rapor verdi ama gerçek uygulamada `androidx.webkit`
// bağımlılığı eksik olduğu için DERLENMEDİ. Saplama, derleyicinin işini taklit eder; taklit
// ayrışır. Artık gerçek Android SDK'sına karşı derleniyoruz.
//
// `local.properties` (sdk.dir) depoya GİRMEZ — makineye özeldir; yoksa ANDROID_HOME okunur.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nsupp-android-sdk"
