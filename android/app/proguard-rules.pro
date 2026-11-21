# 말로 답장 공통 R8 규칙
# 오류 기록에서 줄 번호는 보이게 (파일 이름은 숨김)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute x

# 함께 들어오는 라이브러리가 선택적으로 찾는 것들 (없어도 됨)
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.beans.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn org.jetbrains.annotations.**

# PDF 글자 꺼내기 (pdfbox-android): 글꼴·리소스를 이름으로 찾음
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
