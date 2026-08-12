# Shrinking is disabled for both build types (see app/build.gradle.kts) so this file is not
# consumed by the current build. It is kept accurate so that turning minification on later is a
# one-line change rather than a debugging session.

# Bouncy Castle registers its providers and algorithm implementations reflectively by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization generates serializer() companions that are looked up reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.hisn.app.** {
    *** Companion;
}
-keepclasseswithmembers class org.hisn.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The KDBX model is reflected over by nothing, but its field names appear in XML written to
# disk; keeping them out of the obfuscator's way avoids surprises if that ever changes.
-keep class org.hisn.app.kdbx.** { *; }

# Compose keeps its own rules in the AAR; these only silence warnings about optional desktop
# classes that are never on an Android classpath.
-dontwarn org.jetbrains.annotations.**
