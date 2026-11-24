Put your adhan audio files here (WAV or MP3):

  adhan.wav        — default full adhan (played at every prayer time)
  adhan_fajr.wav   — optional, Fajr-specific adhan
  short_adhan.wav  — optional, short adhan
  catalog.txt      — recordings listed in the settings dialog
  curated/*.mp3    — featured recordings (Makkah, Madinah, Al-Aqsa, ...)
  library/*.mp3    — the rest of the Kiwifu collection

When a file is missing, the app plays a synthesized chime instead,
so sound always works out of the box.

Bundled default recording:
  "Llamada a oración Mezquita Hassan II.wav" by Fraguando
  https://commons.wikimedia.org/wiki/File:Llamada_a_oraci%C3%B3n_Mezquita_Hassan_II.wav
  Licensed under CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/
  Converted to 16-bit PCM WAV for the app.

Selectable recordings (curated/ and library/):
  Source: https://github.com/Kiwifu/adhan-mp3
  231 recordings from muezzins and mosques around the world.
  The source repository does not state a license; check it before
  redistributing the audio separately from this project.

catalog.txt format — one recording per line:

  id|Display name|/assets/audio/adhan/library/file.mp3

To add a recording, drop the MP3 in library/ and add its line to catalog.txt;
no code change is needed.
