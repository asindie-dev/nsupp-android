#!/bin/sh
# nsupp Android SDK — saf-Kotlin çekirdeğin testleri.
#
# NEDEN GRADLE DEĞİL: bu depoda Android SDK'sı kurulu değil; `gradle test` çalışamaz. Çekirdek
# (NsuppApi/NsuppSession/Json) Android'e HİÇ dokunmadığı için kotlinc ile derlenip JVM'de koşar —
# mantık böylece emülatörsüz KANITLANIR. Android kabuğu (`android/` paketi) burada derlenmez;
# onun doğrulaması Gradle derlemesidir (build.gradle.kts).
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
KT=${KOTLIN_LIB:-/opt/homebrew/opt/kotlin/libexec/lib}
# macOS'ta /usr/bin/java bir SAPLAMADIR: var görünür, çalıştırılınca "Unable to locate a Java
# Runtime" der. Bu yüzden "var mı" değil "koşuyor mu" diye bakıyoruz.
JAVA=${JAVA_BIN:-java}
if ! "$JAVA" -version >/dev/null 2>&1; then JAVA=/opt/homebrew/opt/openjdk/bin/java; fi
if ! "$JAVA" -version >/dev/null 2>&1; then echo "JDK bulunamadı: JAVA_BIN ile yolu verin"; exit 1; fi
OUT=$(mktemp -d)

kotlinc -nowarn -Xallow-kotlin-package -cp "$KT/kotlin-test.jar" \
  "$DIR"/src/main/kotlin/com/nsupp/sdk/*.kt \
  "$DIR"/src/test/kotlin/com/nsupp/sdk/*.kt \
  "$DIR"/tools/*.kt -d "$OUT"

"$JAVA" -cp "$OUT:$KT/kotlin-stdlib.jar:$KT/kotlin-test.jar" \
  com.nsupp.sdk.tools.TestRunnerKt \
  com.nsupp.sdk.JsonTest com.nsupp.sdk.NsuppApiTest com.nsupp.sdk.NsuppSessionTest
