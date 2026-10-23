# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html
#
# ---------------------------------------------------------------------------
# Context of this file
# ---------------------------------------------------------------------------
# R8 (isMinifyEnabled / isShrinkResources) is enabled for the release variant.
# Almost everything the app needs is already described by the consumer rules that
# ship inside the dependencies:
#
#   * androidx.media3 forked AARs in app/libs/*.aar -> proguard.txt (renderers,
#     extractors and media source factories built reflectively, JNI entry points);
#   * com.squareup.moshi:moshi                     -> META-INF/proguard/moshi.pro
#   * com.squareup.retrofit2:retrofit              -> META-INF/proguard/retrofit2.pro
#   * com.squareup.okhttp3:okhttp                  -> META-INF/proguard/okhttp3.pro
#   * androidx.room:room-runtime / room-ktx        -> proguard.txt
#   * androidx.compose / kotlinx-coroutines / guava-> META-INF/proguard/*.pro
#
# Only the project specific gaps are declared below. Every rule is justified by
# the reflection (or JNI) lookup it protects; nothing is kept "just in case".
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 1. Reflection performed by the app itself
# ---------------------------------------------------------------------------

# NuvioRenderersFactory.applyMapDv7ToHevcIfSupported() looks the method up by name
# with javaClass.getMethod("setMapDV7ToHevc", boolean). The call is wrapped in
# runCatching, so if R8 renamed or removed the method the DV7 -> HEVC base layer
# mapping would silently stop happening (Dolby Vision profile 7 files would fall
# back to a black/garbled video stream) instead of failing loudly.
-keepclassmembers class androidx.media3.exoplayer.DefaultRenderersFactory {
    public androidx.media3.exoplayer.DefaultRenderersFactory setMapDV7ToHevc(boolean);
}

# ---------------------------------------------------------------------------
# 2. Media3 forked extension decoders
# ---------------------------------------------------------------------------

# androidx.media3.exoplayer.DefaultRenderersFactory instantiates every extension
# renderer with
#     Class.forName("androidx.media3.decoder.<ext>.<Renderer>")
#     .getConstructor(<signature>).newInstance(...)
# where both the class name and the constructor signature are string/reflection
# based. The consumer rules of the forked AARs describe the constructors with
# -keepclassmembers, which only protects them "if the class is kept" and does NOT
# prevent the class itself from being renamed: with minification enabled R8
# obfuscated FfmpegAudioRenderer, Libgav1VideoRenderer and LibiamfAudioRenderer,
# so Class.forName threw ClassNotFoundException and DefaultRenderersFactory
# silently skipped the extension ("Expected if the app was built without the
# extension"). The classes below are the ones actually present on the release
# classpath, so their names and constructors are pinned here.
#
# These renderers are user visible: the app exposes a "Preferisci Software
# (FFmpeg)" decoder fallback and wraps/tracks the FFmpeg renderer in
# NuvioRenderersFactory, while libgav1/libiamf provide the software fallback for
# AV1 video and IAMF audio.
#
# lib-decoder-ffmpeg-release.aar is the one decoder extension that ships no
# proguard.txt, and its native library resolves a Java member by name from
# JNI_OnLoad:
#     GetMethodID(FfmpegAudioDecoder, "growOutputBuffer",
#                 "(Landroidx/media3/decoder/SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;")
# growOutputBuffer() is private and is only ever called from native code, so R8
# renamed it and the library then refused to load:
#     E ffmpeg_jni: JNI_OnLoad: GetMethodID failed
#     W LibraryLoader: Failed to load [ffmpegJNI]
# with the FFmpeg software decoder silently disappearing from the release build
# (verified by comparing the release and non-minified builds on the emulator).
# The whole (6 classes, ~13 KB) package is kept so the Java <-> native contract
# cannot drift again.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
-keep class androidx.media3.decoder.av1.Libgav1VideoRenderer {
    <init>(long, android.os.Handler, androidx.media3.exoplayer.video.VideoRendererEventListener, int);
}
-keep class androidx.media3.decoder.iamf.LibiamfAudioRenderer {
    <init>(android.content.Context, android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
-dontwarn androidx.media3.decoder.ffmpeg.**

# androidx.media3.ui.PlayerView builds these two optional views the same way
# (Class.forName + getConstructor(Context)). Nothing in the app references them
# statically, so R8 removed SphericalGLSurfaceView entirely and renamed
# VideoDecoderGLSurfaceView. Keeping them preserves the PlayerView behaviours the
# project had before minification (spherical/360 playback and video effects)
# instead of silently dropping them.
-keep class androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView {
    <init>(android.content.Context);
}
-keep class androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView {
    <init>(android.content.Context);
}

# JNI entry points are bound by stable names in the .so files shipped by every
# decoder extension (av1, iamf, mpegh, ffmpeg). This is also part of
# proguard-android-optimize.txt, repeated here so the requirement is explicit
# and cannot be lost by a future change of the default rules file.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 3. Moshi code generation
# ---------------------------------------------------------------------------

# Moshi resolves the adapter of a class annotated with @JsonClass(generateAdapter
# = true) in com.squareup.moshi.internal.Util.generatedAdapter() with
# Class.forName("<Model>JsonAdapter") followed by a reflective constructor call.
# R8 cannot follow that lookup, therefore:
#   * the model class must keep its name (the adapter name is derived from it),
#   * the generated adapter class must be kept with its constructors,
#   * the @JsonClass annotation itself must survive as a runtime visible
#     annotation, because it is read before the lookup happens.
# A missing adapter here would break every network payload (TMDB, Stremio addons,
# OpenSubtitles, Simkl, TorBox) at parse time.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, Signature, InnerClasses, EnclosingMethod
-keepnames @com.squareup.moshi.JsonClass class *
-keep class **JsonAdapter {
    <init>(...);
}

# Every Moshi instance in the app is built with
# Moshi.Builder().add(KotlinJsonAdapterFactory()).build(): every model that is not
# covered by code generation is mapped through kotlin-reflect, which finds the
# @kotlin.Metadata annotation of the class and reads the Kotlin parameter and
# property names out of it (that is why obfuscating the JVM field names is safe).
# Two separate requirements:
#   * com.squareup.moshi.internal.Util resolves the annotation class with
#     Class.forName("kotlin.Metadata"), so its name must survive;
#   * the annotation itself must stay on the models. Retention was verified on the
#     minified build: TmdbMovieDto still carries the full @kotlin.Metadata blob in
#     the release dex. -keepkotlinmetadata states the requirement explicitly; it
#     was measured to leave the APK byte-for-byte identical, i.e. it is a
#     guarantee against future default changes, not a change by itself.
-keepkotlinmetadata
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# 4. nextlib MediaInfo (nextlib-mediainfo-local.aar ships an empty proguard.txt)
# ---------------------------------------------------------------------------

# The JNI layer of libmediainfo.so resolves the DTO classes and their fields by
# name with GetFieldID/GetStaticFieldID, so obfuscating this package would break
# the frame-rate / stream inspection used before playback. The package is small
# (one classes.jar of ~26 KB) and is entirely used by the player, so keeping it
# costs nothing measurable while removing a real risk.
-keep class io.github.anilbeesetti.nextlib.mediainfo.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.mediainfo.**

# ---------------------------------------------------------------------------
# 5. Moshi DTOs handled by kotlin-reflect (KotlinJsonAdapterFactory)
# ---------------------------------------------------------------------------

# The classes below have no @JsonClass annotation, so Moshi maps them with
# KotlinJsonAdapterFactory: it instantiates them through their Kotlin primary
# constructor and reads their properties through kotlin-reflect. The app never
# calls those constructors/getters directly (it only passes the Class object to
# moshi.adapter(...)), so R8 full mode removes them, empties the class and can
# even merge the emptied class into a synthetic holder. StremioStreamResponse hit
# exactly that path: the class left in the dex had no `streams` field and carried
# the ABSTRACT flag, and Moshi aborted the app at startup with
# "Cannot serialize abstract class".
#
# DTOs annotated with @JsonClass(generateAdapter = true) (OpenSubtitles, Simkl,
# TorBox) are not listed here: their generated adapters reference the model
# directly, so R8 keeps everything they need.
#
# Only the models are kept; the class names stay obfuscated, so this costs only
# the members that Moshi needs at runtime.
-keep,allowobfuscation class com.example.data.api.TmdbPaginatedResponse { *; }
-keep,allowobfuscation class com.example.data.api.Tmdb*Dto { *; }

-keep,allowobfuscation class com.example.data.stremio.InstalledAddon { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioManifest { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioManifestBehaviorHints { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioResource { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioCatalogDefinition { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioCatalogExtra { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioCatalogResponse { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioMetaItem { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioStreamResponse { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioStreamItem { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioStreamBehaviorHints { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioProxyHeaders { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioSubtitleResponse { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioSubtitle { *; }
-keep,allowobfuscation class com.example.data.stremio.StremioSubtitleOptions { *; }

# Registry file and user binding store (private DTOs nested in the singletons)
-keep,allowobfuscation class com.example.data.stremio.provider.ProviderBindingRegistry$RegistryFile { *; }
-keep,allowobfuscation class com.example.data.stremio.provider.ProviderBindingRegistry$RegistryEntry { *; }
-keep,allowobfuscation class com.example.data.stremio.provider.UserProviderBindingStore$StoredFile { *; }
-keep,allowobfuscation class com.example.data.stremio.provider.UserProviderBindingStore$StoredBinding { *; }

# GitHub release feed used by the in-app updater
-keep,allowobfuscation class com.example.data.update.GitHubReleaseDto { *; }
-keep,allowobfuscation class com.example.data.update.GitHubReleaseAssetDto { *; }

# ---------------------------------------------------------------------------
# 6. Warnings from optional/compile-only dependencies
# ---------------------------------------------------------------------------

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
