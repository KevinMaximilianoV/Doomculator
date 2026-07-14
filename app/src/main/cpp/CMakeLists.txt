cmake_minimum_required(VERSION 3.22.1)
project("doom_native")

# ── Collect doom-generic sources ──────────────────────────────────────────────
# The cloned repo puts its sources in doom/doomgeneric/ (one level deeper than
# the repo root).
#
# NOTE: i_video.c is KEPT — in doomgeneric forks it's rewritten to call
# DG_DrawFrame()/DG_ScreenBuffer directly, it's not a duplicate of our
# android_doom.c. Only exclude files with real, unconditional platform
# library dependencies we can't satisfy on Android:
#   - i_sound.c                          → stubbed by i_sound_stub.c
#   - i_sdlsound.c / i_sdlmusic.c         → require SDL2/SDL_mixer
#   - i_allegrosound.c / i_allegromusic.c → require Allegro
#   - doomgeneric_<platform>.c           → alternate platform entry points
#                                           (android_doom.c is ours)
file(GLOB_RECURSE DOOM_SRC "doom/doomgeneric/*.c")
list(FILTER DOOM_SRC EXCLUDE REGEX
    ".*/(i_sound|i_sdlsound|i_sdlmusic|i_allegrosound|i_allegromusic|i_main|i_net|doomgeneric_[a-z]+)\\.c$")

add_library(doom-native SHARED
    android_doom.c       # Android platform implementation (DG_* callbacks + JNI)
    i_sound_stub.c       # No-op SFX — avoids Linux OSS/ALSA compile errors
    i_music_native.c     # Real music: MUS→MIDI (mus2mid.c) + MediaPlayer bridge
    ${DOOM_SRC}
)

target_include_directories(doom-native PRIVATE doom/doomgeneric)

# Match these to doomgeneric.h — change if the header uses different values
target_compile_definitions(doom-native PRIVATE
    DOOMGENERIC_RESX=320
    DOOMGENERIC_RESY=200
)

find_library(log-lib   log)
find_library(android-lib android)

target_link_libraries(doom-native ${log-lib} ${android-lib})
