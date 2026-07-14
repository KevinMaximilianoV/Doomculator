#ifndef ANDROID_DOOM_H
#define ANDROID_DOOM_H

#include <jni.h>

#define DOOM_PATH_MAX 512

/* Shared JNI handles, defined in android_doom.c. The music backend
 * (i_music_native.c) calls back into the same DoomSurfaceView instance
 * used for video frames, so it reuses these instead of keeping its own. */
extern JavaVM *g_jvm;
extern jobject g_view;
extern char    g_files_dir[DOOM_PATH_MAX];

/* Attaches the calling native thread to the JVM if it isn't already.
 * *attached is set to 1 if this call attached it (caller must later call
 * doom_detach_env), or 0 if the thread was already attached (caller must
 * not detach it — that would rip the JNIEnv out from under its owner). */
JNIEnv *doom_attach_env(int *attached);
void    doom_detach_env(int attached);

/* Resolves the music-callback method IDs on g_view's class. Called once
 * from nativeInit() right after g_view itself is set up. */
void Doom_InitMusicJNI(JNIEnv *env, jclass cls);

#endif /* ANDROID_DOOM_H */
