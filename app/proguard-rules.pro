# Release shrinking (R8). Decision and reasoning: docs/architecture.md, "Release build: R8".
#
# Names stay readable: the in-app crash log and the diagnostic report are the only way to
# see a stack trace on a phone without adb, and they are copied into bug reports as they are.
# Shrinking and optimisation give the size; obfuscation would only make those reports useless.
-dontobfuscate

-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization, Hilt, OkHttp, coroutines, DataStore and Compose ship their own rules.
# Every stored model is read through an explicit `serializer()` and its JSON keys are string
# constants in the generated serializer, so shrinking cannot rename a stored field.

# Glance instantiates an ActionCallback by class name (Class.forName + no-arg constructor),
# and a placed widget keeps that name in its PendingIntents until it is redrawn.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# WorkManager 2.7 (Glance's) and its Room 2.2 build two things by name: Room the generated
# WorkDatabase_Impl (Class.forName + no-arg constructor, from androidx.startup before
# Application.onCreate), WorkManager the InputMerger named in each work request. Their own rules
# say `-keep class * extends …` with no members: under R8 full mode that keeps the class but not
# its constructor, R8 removed both, and v0.2.0 died at every launch in WorkManagerInitializer.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.InputMerger { <init>(); }

# Wire: the Kotlin generator wires ADAPTERs statically, but ProtoAdapter.get(Class) and the
# runtime's enum lookups reflect on the generated classes. They are few and small: keep them whole.
-keep class io.kud.tutti.protocol.atv.proto.** { *; }

# BouncyCastle (certificate generation for Android TV pairing) references JNDI and
# optional JCA classes that Android does not have; nothing here reaches them.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
