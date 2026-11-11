# CLAUDE.md

## Project
- Repo: fdvcam (flashsss/fdvcam)
- Modul Xposed kamera virtual (untuk testing), awalnya salinan persis hazbu/xCam (GPL-3.0; jaga LICENSE + atribusi).
- Stack: Kotlin/Java, Gradle, LibXposed API 101, Android 9+.

## Perintah
- Build: `./gradlew assembleDebug`
- Test: `./gradlew test`
- Lint/format: _(isi nanti)_

## Struktur
- `app/src/main/java/com/suryasukma/vcamtest/`: `hooks/` (Camera1/2, ImageReader, Capture, Audio, WebRtc), `core/` (engine, capture, surface, audio), `xposed/` (entry), `ui/` (MainActivity, EditorActivity, CropOverlayView), `media/` (EditSpec, MediaEditor, MediaLibrary).
- Edit: editor membakar crop/rotate/flip/adjust/trim ke `virtual.mp4` via Media3 Transformer; file asli disimpan di `source.*` (reset ke original).

## Konvensi
- Bahasa komunikasi: Indonesia. Kode, nama variabel, dan commit message: Inggris.
- Perubahan kecil dan fokus; jangan refactor di luar permintaan.
- Jangan baca/ubah `node_modules`, `dist`, `build`, atau file log.

## Git
- Kerja di branch `claude/wizardly-edison-8a0gzf`.
- Jangan buat PR kecuali diminta.

## Hemat token
- Jawaban singkat; tanpa ringkasan panjang setelah selesai.
- Baca hanya file yang relevan; gunakan Grep/Glob sebelum membaca file besar.
