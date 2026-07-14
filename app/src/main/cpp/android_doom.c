#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include "doom/doomgeneric/doomgeneric.h"
#include "android_doom.h"

#define TAG  "DOOM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── JNI handles (shared with i_music_native.c via android_doom.h) ────────── */
JavaVM    *g_jvm    = NULL;
jobject    g_view   = NULL;   /* global ref → DoomSurfaceView instance */
char       g_files_dir[DOOM_PATH_MAX] = {0};
static jmethodID  g_onFrame = NULL;  /* DoomSurfaceView.onDoomFrame([III)V   */

JNIEnv *doom_attach_env(int *attached) {
    JNIEnv *env;
    *attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        *attached = 1;
    }
    return env;
}

void doom_detach_env(int attached) {
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* ── Key event queue ─────────────────────────────────────────────────────── */
/* Each entry: high byte = 1 (pressed) or 0 (released), low byte = doom key  */
#define KQ_SIZE 64
static uint16_t  kq[KQ_SIZE];
static int       kq_head = 0, kq_tail = 0;
static pthread_mutex_t kq_mtx = PTHREAD_MUTEX_INITIALIZER;

/* ── Pixel scratch buffer (DOOM 0x00RRGGBB → Android 0xFFRRGGBB) ─────────── */
static jint pixel_buf[DOOMGENERIC_RESX * DOOMGENERIC_RESY];

/* ══════════════════════════════════════════════════════════════════════════
   DoomGeneric platform callbacks
   ══════════════════════════════════════════════════════════════════════════ */

void DG_Init(void) { LOGI("DG_Init"); }

void DG_DrawFrame(void) {
    if (!g_jvm || !g_view) return;

    static int frame_count = 0;
    if ((frame_count++ % 35) == 0) LOGI("DG_DrawFrame #%d", frame_count);

    int attached = 0;
    JNIEnv *env = doom_attach_env(&attached);

    /* Fix alpha: doom writes 0x00RRGGBB, Android Bitmap needs 0xFFRRGGBB */
    int n = DOOMGENERIC_RESX * DOOMGENERIC_RESY;
    for (int i = 0; i < n; i++)
        pixel_buf[i] = (jint)(DG_ScreenBuffer[i] | 0xFF000000u);

    jintArray arr = (*env)->NewIntArray(env, n);
    if (arr) {
        (*env)->SetIntArrayRegion(env, arr, 0, n, pixel_buf);
        (*env)->CallVoidMethod(env, g_view, g_onFrame,
                               arr, (jint)DOOMGENERIC_RESX, (jint)DOOMGENERIC_RESY);
        (*env)->DeleteLocalRef(env, arr);
    }

    doom_detach_env(attached);
}

void DG_SleepMs(uint32_t ms) {
    struct timespec ts = { (time_t)(ms / 1000), (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, NULL);
}

uint32_t DG_GetTicksMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

int DG_GetKey(int *pressed, unsigned char *doomKey) {
    pthread_mutex_lock(&kq_mtx);
    if (kq_head == kq_tail) { pthread_mutex_unlock(&kq_mtx); return 0; }
    uint16_t ev = kq[kq_head];
    kq_head = (kq_head + 1) % KQ_SIZE;
    pthread_mutex_unlock(&kq_mtx);
    *pressed = (ev >> 8) & 0x01;
    *doomKey = (unsigned char)(ev & 0xFF);
    return 1;
}

void DG_SetWindowTitle(const char *title) { LOGI("Title: %s", title); }

/* ══════════════════════════════════════════════════════════════════════════
   JNI entry points (called from DoomSurfaceView.kt)
   ══════════════════════════════════════════════════════════════════════════ */

JNIEXPORT void JNICALL
Java_com_example_calculadorachida_DoomSurfaceView_nativeInit(
        JNIEnv *env, jobject thiz, jstring jWadPath, jstring jFilesDir) {

    (*env)->GetJavaVM(env, &g_jvm);
    if (g_view) (*env)->DeleteGlobalRef(env, g_view);
    g_view = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_onFrame = (*env)->GetMethodID(env, cls, "onDoomFrame", "([III)V");
    Doom_InitMusicJNI(env, cls);

    const char *wad  = (*env)->GetStringUTFChars(env, jWadPath,  NULL);
    const char *fdir = (*env)->GetStringUTFChars(env, jFilesDir, NULL);

    strncpy(g_files_dir, fdir, sizeof(g_files_dir) - 1);
    g_files_dir[sizeof(g_files_dir) - 1] = '\0';

    /* Diagnostic: confirm the WAD is actually openable from native code before
     * handing it to doomgeneric. Java's File.exists() can return true even when
     * fopen() fails here (scoped storage enforces stricter rules at the native
     * level on API 29+), and D_FindIWAD's I_Error() on failure calls exit(),
     * which kills the whole process — so this check turns a hard crash with no
     * explanation into a clear logcat line. */
    FILE *probe = fopen(wad, "rb");
    if (!probe) {
        LOGE("WAD not openable at '%s': %s", wad, strerror(errno));
    } else {
        fseek(probe, 0, SEEK_END);
        long size = ftell(probe);
        fclose(probe);
        LOGI("WAD ok: '%s' (%ld bytes)", wad, size);
    }

    /* Work from the app's files dir so DOOM saves configs and saves there */
    chdir(fdir);

    /* doomgeneric_Create stores a POINTER to this argv (myargv = argv), not a
     * copy — M_CheckParm() etc. dereference it later from the DoomThread game
     * loop, long after nativeInit() returns. A stack-local array here would be
     * reclaimed by the time that happens, causing a segfault deep in the tick
     * loop. Keep both the array and the wad string in static storage so they
     * live for the whole process, not just this call. */
    static char s_wad[512];
    static char *s_argv[3];
    strncpy(s_wad, wad, sizeof(s_wad) - 1);
    s_wad[sizeof(s_wad) - 1] = '\0';
    s_argv[0] = "doom";
    s_argv[1] = "-iwad";
    s_argv[2] = s_wad;

    doomgeneric_Create(3, s_argv);
    LOGI("doomgeneric_Create done, wad=%s", s_wad);

    (*env)->ReleaseStringUTFChars(env, jWadPath,  wad);
    (*env)->ReleaseStringUTFChars(env, jFilesDir, fdir);
}

JNIEXPORT void JNICALL
Java_com_example_calculadorachida_DoomSurfaceView_nativeTick(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    doomgeneric_Tick();
}

JNIEXPORT void JNICALL
Java_com_example_calculadorachida_DoomSurfaceView_nativeSendKey(
        JNIEnv *env, jobject thiz, jint doomKey, jboolean pressed) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&kq_mtx);
    int next = (kq_tail + 1) % KQ_SIZE;
    if (next != kq_head) {
        kq[kq_tail] = (uint16_t)(((pressed ? 1 : 0) << 8) | (doomKey & 0xFF));
        kq_tail = next;
    }
    pthread_mutex_unlock(&kq_mtx);
}
