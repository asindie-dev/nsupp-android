package com.nsupp.sdk.tools

/**
 * Minik yansıma tabanlı test koşucusu.
 *
 * NEDEN VAR: paketin gerçek testleri Gradle + JUnit ile koşar (build.gradle.kts'e bakın). Bu depoda
 * Android SDK'sı ve Gradle önbelleği YOK; testleri yine de KANITLAMAK için `kotlinc` ile derleyip
 * `@kotlin.test.Test` işaretli metotları burada çağırıyoruz. Kotlin'in assert'leri (kotlin-test)
 * çerçeve bulamazsa AssertionError fırlatır — doğrulama gücü aynıdır.
 *
 * ÜRÜNE GİRMEZ: `src/` altında değildir, AAR'a paketlenmez.
 */
fun main(args: Array<String>) {
    var gecen = 0
    val kalanlar = mutableListOf<String>()
    for (ad in args) {
        val klass = Class.forName(ad)
        for (m in klass.declaredMethods.sortedBy { it.name }) {
            if (m.annotations.none { it.annotationClass.qualifiedName == "kotlin.test.Test" }) continue
            val ornek = klass.getDeclaredConstructor().newInstance()
            try {
                m.invoke(ornek)
                gecen++
                println("  ✓ ${klass.simpleName}.${m.name}")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                kalanlar.add("${klass.simpleName}.${m.name}: ${e.targetException}")
                println("  ✗ ${klass.simpleName}.${m.name} — ${e.targetException.message}")
            }
        }
    }
    println("\n$gecen geçti, ${kalanlar.size} kaldı")
    if (kalanlar.isNotEmpty()) kotlin.system.exitProcess(1)
}
