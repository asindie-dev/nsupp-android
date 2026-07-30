@file:Suppress("PACKAGE_OR_CLASSIFIER_REDECLARATION")
package kotlin.test

/**
 * `kotlin.test.Test` bu depoda YOK: JVM'de o annotation JUnit'e takma addır (kotlin-test-junit),
 * JUnit jar'ı ise burada bulunmuyor. Testlerin importu DOĞRU kalsın (Gradle + JUnit ile gerçek
 * annotation kullanılacak) diye yerel koşum için bir gölge tanım veriyoruz.
 *
 * ÜRÜNE VE GRADLE DERLEMESİNE GİRMEZ: `tools/` klasörü `src/` dışındadır; build.gradle.kts
 * kaynak kümesine dahil etmez.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
