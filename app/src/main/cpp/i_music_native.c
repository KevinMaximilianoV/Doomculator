/*
 * Real music backend.
 *
 * DOOM stores music as MUS-format lumps inside the WAD (already on-device,
 * since they're part of the shareware doom1.wad the user owns). mus2mid.c /
 * memio.c — already present in this doomgeneric checkout, unmodified — do a
 * lossless format conversion from MUS to a standard MIDI file. Android's own
 * MediaPlayer can play a standard .mid file directly using its built-in
 * software synth, so there's no need for a custom OPL2/FM emulator: convert
 * once per song change, write the result to a temp file, hand the path to a
 * small Kotlin MediaPlayer wrapper on DoomSurfaceView.
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "android_doom.h"
#include "doom/doomgeneric/i_sound.h"
#include "doom/doomgeneric/mus2mid.h"
#include "doom/doomgeneric/memio.h"

#define TAG  "DOOM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jmethodID g_musicPlay      = NULL;  /* musicPlay(String, boolean)V */
static jmethodID g_musicStop      = NULL;  /* musicStop()V                */
static jmethodID g_musicPause     = NULL;  /* musicPause()V               */
static jmethodID g_musicResume    = NULL;  /* musicResume()V              */
static jmethodID g_musicSetVolume = NULL;  /* musicSetVolume(float)V      */

static int     g_song_counter  = 0;
static boolean g_music_playing = false;

void Doom_InitMusicJNI(JNIEnv *env, jclass cls) {
    g_musicPlay      = (*env)->GetMethodID(env, cls, "musicPlay",      "(Ljava/lang/String;Z)V");
    g_musicStop      = (*env)->GetMethodID(env, cls, "musicStop",      "()V");
    g_musicPause     = (*env)->GetMethodID(env, cls, "musicPause",     "()V");
    g_musicResume    = (*env)->GetMethodID(env, cls, "musicResume",    "()V");
    g_musicSetVolume = (*env)->GetMethodID(env, cls, "musicSetVolume", "(F)V");
}

void I_InitMusic(void)     { LOGI("I_InitMusic"); }
void I_ShutdownMusic(void) { I_StopSong(); }

void *I_RegisterSong(void *data, int len) {
    MEMFILE *instream  = mem_fopen_read(data, (size_t)len);
    MEMFILE *outstream = mem_fopen_write();
    boolean  failed    = mus2mid(instream, outstream);  // returns 0 on success, 1 on failure

    void *handle = NULL;
    if (!failed) {
        void   *midbuf;
        size_t  midlen;
        mem_get_buf(outstream, &midbuf, &midlen);

        char path[DOOM_PATH_MAX];
        snprintf(path, sizeof(path), "%s/doommusic_%d.mid", g_files_dir, g_song_counter++);

        FILE *f = fopen(path, "wb");
        if (f) {
            fwrite(midbuf, 1, midlen, f);
            fclose(f);
            handle = strdup(path);
            LOGI("Music registered: %s (%zu bytes)", path, midlen);
        } else {
            LOGE("Could not write temp midi file '%s': %s", path, strerror(errno));
        }
    } else {
        LOGE("mus2mid conversion failed (not valid MUS data?)");
    }

    mem_fclose(instream);
    mem_fclose(outstream);
    return handle;
}

void I_UnRegisterSong(void *handle) {
    if (!handle) return;
    remove((const char *)handle);
    free(handle);
}

void I_PlaySong(void *handle, boolean looping) {
    if (!handle || !g_musicPlay) {
        LOGE("I_PlaySong: skipped (handle=%p, g_musicPlay=%p)", handle, (void *)g_musicPlay);
        return;
    }
    LOGI("I_PlaySong: calling musicPlay('%s', looping=%d)", (const char *)handle, looping);
    int attached;
    JNIEnv *env = doom_attach_env(&attached);
    jstring jpath = (*env)->NewStringUTF(env, (const char *)handle);
    (*env)->CallVoidMethod(env, g_view, g_musicPlay, jpath, (jboolean)looping);
    (*env)->DeleteLocalRef(env, jpath);
    doom_detach_env(attached);
    g_music_playing = true;
}

void I_StopSong(void) {
    if (!g_musicStop) return;
    int attached;
    JNIEnv *env = doom_attach_env(&attached);
    (*env)->CallVoidMethod(env, g_view, g_musicStop);
    doom_detach_env(attached);
    g_music_playing = false;
}

void I_PauseSong(void) {
    if (!g_musicPause) return;
    int attached;
    JNIEnv *env = doom_attach_env(&attached);
    (*env)->CallVoidMethod(env, g_view, g_musicPause);
    doom_detach_env(attached);
}

void I_ResumeSong(void) {
    if (!g_musicResume) return;
    int attached;
    JNIEnv *env = doom_attach_env(&attached);
    (*env)->CallVoidMethod(env, g_view, g_musicResume);
    doom_detach_env(attached);
}

void I_SetMusicVolume(int volume) {
    if (!g_musicSetVolume) return;
    int attached;
    JNIEnv *env = doom_attach_env(&attached);
    (*env)->CallVoidMethod(env, g_view, g_musicSetVolume, (jfloat)(volume / 127.0f));
    doom_detach_env(attached);
}

boolean I_MusicIsPlaying(void) { return g_music_playing; }
