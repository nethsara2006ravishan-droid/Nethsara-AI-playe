# NETHSARA AI PLAYER — Whisper Subtitle Update

## Included in this update
- Existing Media3 video player, video picker/library and playback speed controls retained.
- SRT import/export, subtitle size, delay and display retained.
- Whisper.cpp Android speech recognition dependency integrated into the AI subtitle button.
- Selected video's audio track is decoded to mono 16 kHz WAV for transcription.
- Whisper multilingual `ggml-base.bin` model downloads on first AI use (about 140 MB; storage/network needed once).
- Speech segments are translated into Sinhala using a no-key Google Translate web endpoint.
- Generated subtitle segments retain Whisper segment timestamps and can be exported as SRT.

## Generate Sinhala subtitles
1. Open a video.
2. Tap **Generate Sinhala Subtitles (Whisper Offline)**.
3. First run downloads the Whisper model. Recognition then runs on-device/offline.
4. Sinhala translation currently uses an online no-key translation endpoint, so internet is required during translation. This is not a fully offline Sinhala translation model.
5. Export the resulting SRT using Export .SRT.

## Notes / limitations
- Whisper recognition runs locally after model download. Model download requires internet and free storage.
- Sinhala translation is online (no API key), not offline; service availability/rate limits can change.
- The Whisper base model and CPU processing can be slow on lower-end phones. Long videos may require substantial RAM and processing time.
- Audio decoder compatibility follows Android MediaCodec/device support. Some videos may fail if their audio codec cannot be decoded.
- This package has not been Gradle-built or installed/tested in this environment. Build via the included GitHub Actions workflow before relying on it.
