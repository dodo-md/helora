
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault, *Annotation*

-keep class com.kyant.taglib.** { *; }

-keep class org.jaudiotagger.tag.** { *; }
-dontwarn org.jaudiotagger.**

-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.sound.sampled.**
-dontwarn javax.swing.filechooser.FileFilter
-dontwarn javax.lang.model.**

-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.exoplayer.ffmpeg.** { *; }

-keep class androidx.media3.decoder.midi.** { *; }
-keep class com.jsyn.** { *; }
-keep class com.softsynth.** { *; }
-dontwarn com.jsyn.**
-dontwarn com.softsynth.**

-keepclassmembers class com.lostf1sh.pixelplayeross.data.model.** { *; }

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.lostf1sh.pixelplayeross.data.preferences.PreferenceBackupEntry { *; }
-keep class com.lostf1sh.pixelplayeross.data.backup.model.** { *; }
-keep class com.lostf1sh.pixelplayeross.data.backup.module.** { *; }
-keep class com.lostf1sh.pixelplayeross.data.database.FavoritesEntity { *; }
-keep class com.lostf1sh.pixelplayeross.data.database.SongEngagementEntity { *; }
-keep class com.lostf1sh.pixelplayeross.data.database.LyricsEntity { *; }
-keep class com.lostf1sh.pixelplayeross.data.database.SearchHistoryEntity { *; }
-keep class com.lostf1sh.pixelplayeross.data.database.TransitionRuleEntity { *; }

-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.cio.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**

-keep class com.atilika.kuromoji.** { *; }
-keepnames class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

-keep class net.sourceforge.pinyin4j.** { *; }
-keepclassmembers class net.sourceforge.pinyin4j.** { *; }
-dontwarn net.sourceforge.pinyin4j.**

-keep class * extends androidx.glance.appwidget.action.ActionCallback { <init>(); }

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static timber.log.Timber$Tree tag(java.lang.String);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# =============================================================================
# NEWPIPE EXTRACTOR (YouTube Music)
# =============================================================================
# The extractor discovers services reflectively and binds JSON responses by
# field name, so its class and member names must survive shrinking.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# nanojson - reflective JSON binding used throughout the extractor.
-keep class com.grack.nanojson.** { *; }

# Rhino evaluates YouTube's player JavaScript to deobfuscate stream signatures
# (the "nsig" challenge). It reflects on its own classes and generates class
# files at runtime, so anything short of a full keep produces streams that work
# in debug and return 403 in release.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# jsoup - HTML fallback parsing.
-keep class org.jsoup.** { *; }

-dontwarn org.nibor.autolink.**
