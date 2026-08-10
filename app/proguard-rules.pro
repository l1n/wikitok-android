# kotlinx.serialization: keep generated serializers reachable via companions
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.novalinium.wikitok.** {
    *** Companion;
}
-keepclasseswithmembers class com.novalinium.wikitok.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.novalinium.wikitok.**$$serializer { *; }
