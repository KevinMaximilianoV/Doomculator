/*
 * No-op SOUND EFFECTS implementation (music is real — see i_music_native.c).
 *
 * The original i_sound.c needs Linux OSS/ALSA headers that don't exist on
 * Android, and i_sdlsound.c / i_sdlmusic.c / i_allegro*.c need SDL2/Allegro
 * which we don't link against either. This file implements the sfx half of
 * the i_sound.h interface as silent no-ops so the linker is satisfied
 * without a real mixer.
 *
 * It also defines the handful of extern config variables that m_config.c
 * references (snd_*, opl_io_port, timidity_cfg_path) and the two sound/music
 * module structs declared in the header — these were previously defined
 * inside the excluded platform sound files.
 *
 * To add real sound effects later, implement these against AAudio or
 * OpenSL ES (decode the WAD's DMX-format sfx lumps into PCM and mix them).
 */
#include <stddef.h>
#include "doom/doomgeneric/i_sound.h"

void I_InitSound(boolean use_sfx_prefix)                              { (void)use_sfx_prefix; }
void I_ShutdownSound(void)                                            {}
int  I_GetSfxLumpNum(sfxinfo_t *sfxinfo)                              { (void)sfxinfo; return 0; }
void I_UpdateSound(void)                                              {}
void I_UpdateSoundParams(int channel, int vol, int sep)               { (void)channel; (void)vol; (void)sep; }
int  I_StartSound(sfxinfo_t *sfxinfo, int channel, int vol, int sep)  { (void)sfxinfo; (void)channel; (void)vol; (void)sep; return -1; }
void I_StopSound(int channel)                                         { (void)channel; }
boolean I_SoundIsPlaying(int channel)                                 { (void)channel; return false; }
void I_PrecacheSounds(sfxinfo_t *sounds, int num_sounds)              { (void)sounds; (void)num_sounds; }

/* Music (I_InitMusic, I_PlaySong, etc.) is implemented for real in
 * i_music_native.c — defining no-op versions here would be a duplicate
 * symbol at link time. */

void I_BindSoundVariables(void)                                       {}
/* I_InitTimidityConfig is already defined in doomgeneric's dummy.c */

/* Config variables m_config.c binds to the options menu / config file */
int   snd_sfxdevice       = SNDDEVICE_NONE;
int   snd_musicdevice     = SNDDEVICE_NONE;
int   snd_samplerate      = 44100;
int   snd_cachesize       = 0;
int   snd_maxslicetime_ms = 28;
char *snd_musiccmd        = "";
int   opl_io_port         = 0;
char *timidity_cfg_path   = "";

/* Module tables — zero-initialised, never selected since the devices above are NONE */
sound_module_t sound_pcsound_module = {0};
music_module_t music_opl_module     = {0};
#ifdef FEATURE_SOUND
sound_module_t DG_sound_module      = {0};
music_module_t DG_music_module      = {0};
#endif
