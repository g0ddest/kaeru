# R8 rules for the minified release build.
#
# Verified against the current dependency set (Retrofit 3, OkHttp 5, Room 2.8, Hilt 2.58,
# Coil 3, kotlinx-serialization 1.11): `:app:assembleRelease` produces no missing_rules.txt
# and no R8 warnings, so those libraries' own consumer rules are sufficient and nothing is
# kept here for them. Add a rule only when R8 reports it missing.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.kaeru.**$$serializer { *; }
-keepclassmembers class app.kaeru.** { *** Companion; }
-keepclasseswithmembers class app.kaeru.** { kotlinx.serialization.KSerializer serializer(...); }
