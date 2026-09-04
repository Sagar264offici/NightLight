# Retrofit interfaces + Gson DTOs are reflected upon; keep them intact.
-keep,allowobfuscation,allowshrinking interface com.nightlight.app.data.api.** { *; }
-keep class com.nightlight.app.data.api.dto.** { *; }

# Room entities are read via reflection by the generated DAO implementations.
-keep class com.nightlight.app.data.db.entity.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Gson field names must survive.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Media3, Retrofit, OkHttp, Glide and Room ship their own consumer rules.

-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**