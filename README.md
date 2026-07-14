# Calculadorachida

An Android calculator app with an embedded Doom engine, built with a native C/C++ layer via CMake/NDK.

## Credits

The Doom engine at `app/src/main/cpp/doom` is based on [doomgeneric](https://github.com/ozkl/doomgeneric) by [ozkl](https://github.com/ozkl), a portable reimplementation of Doom's engine designed to make porting Doom to new platforms easy. doomgeneric is itself derived from the original Doom source released by id Software.

The Android-specific integration (`app/src/main/cpp/android_doom.c`, `android_doom.h`, and related native/sound glue code) was written for this project on top of doomgeneric.

## License

This project incorporates GPL-2.0 licensed code (doomgeneric / id Software's Doom source) and is therefore distributed under the terms of the **GNU General Public License v2.0**. See [LICENSE](LICENSE) for the full text.
