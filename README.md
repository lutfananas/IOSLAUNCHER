# iOS Launcher untuk Android 17

Launcher Android (target API 37 / Android 17, minimal Android 12) dengan tampilan & perilaku iOS.

## Fitur

- **Icon pack terpasang terbaca otomatis.** Mendukung format ADW / Nova / Apex / GO / Lawnchair (`appfilter.xml`,
  termasuk `iconback`, `iconmask`, `iconupon`, `scale`). Pilih di *Settings → Icon Pack*.
- **Bentuk ikon 1:1 dengan iOS.** Ikon dipotong dengan *continuous-corner squircle* milik Apple (kurva Bézier yang sama
  dengan `UIBezierPath` `.continuous`, radius 22,37% dari ukuran ikon). Bentuk yang sama juga dipakai untuk dock,
  kartu App Library, menu dan alert. Opsi *Fit Icons to iOS Shape* menskalakan artwork icon pack agar memenuhi squircle.
- **Animasi buka kunci seperti iOS 26.** Ikon dan folder melayang masuk dari tepi layar, berjenjang baris demi baris, dan mendarat dengan pantulan pegas halus; dock naik dari bawah. Setelah itu efek paralaks (ikon bergeser mengikuti kemiringan HP) dan kilau kaca yang mengikuti arah miring aktif.
- **App Library.** Geser ke kiri melewati halaman terakhir: kategori otomatis (Suggestions, Recently Added, Social,
  Productivity & Finance, Entertainment, dll.) dengan 3 ikon besar + klaster 4 ikon kecil, kategori bisa dibuka penuh,
  plus pencarian dengan daftar A–Z dan indeks huruf.
- **Fullscreen.** Status bar disembunyikan (swipe dari tepi atas untuk menampilkannya sementara).
- **Indikator baterai / Wi-Fi / sinyal di pojok kanan atas Page 1.** Baris pertama Page 1 dikosongkan kecuali satu
  glyph gabungan (dinaikkan seperempat ukuran ikon dari baris pertama, ukuran 70% ikon, dengan jam digital di atasnya): cincin baterai, ikon Wi-Fi di dalamnya, dan titik sinyal seluler, dengan persen baterai di bawahnya.
  Diadaptasi dari [Duo Launcher](https://github.com/jakesgoodapps/DuoLauncher) (lisensi MIT, lihat `THIRD_PARTY_NOTICES.md`).
- **Ukuran ikon bisa diatur, terpisah untuk Home Screen dan Dock** (50%–130% dari ukuran ikon iOS),
  plus opsi **Hide App Names** untuk menyembunyikan nama ikon. Ada di *Settings → Icon Size / Icons*.
- **Folder.** Seret satu ikon ke atas ikon lain dan tahan sebentar untuk membuat folder (namanya otomatis sesuai
  kategori, bisa diganti dengan mengetuk judul saat folder dibuka). Di mode edit, folder punya pegangan di sisi kiri/kanan
  (lebar) dan bawah (tinggi) yang bisa ditarik per petak grid. Folder 1×1 menampilkan pratinjau 3×3; folder besar
  menampilkan aplikasinya langsung dan bisa diketuk untuk membuka aplikasi. Ukuran cepat juga ada di menu tekan-lama folder.
- **Free Placement (tanpa auto-fill).** *Settings → Home Screen Grid → Free Placement*: ikon tetap di petak tempat
  dilepas, petak kosong dibiarkan kosong; melepas ikon di atas ikon lain menukar posisinya.
- **Custom icon per aplikasi.** Tekan lama ikon → *Edit Icon*: pilih gambar sendiri dari galeri, atau ambil ikon mana pun
  dari icon pack yang terinstal (dengan pencarian dan saran ikon yang cocok). Hasilnya dipotong ke bentuk ikon iOS.
  *Reset to Default Icon* mengembalikan ikon asli.
- **Grid baris × kolom bisa diatur 3–8** (sampai 8×8). Ikon dan nama otomatis mengecil di grid yang padat.
- **Dock bisa dimatikan**, atau disembunyikan hanya di Page 1 (*Hide Dock on Page 1 Only*; Page 1 mendapat satu baris ikon tambahan di ruang dock, dan dock muncul lagi saat digeser ke halaman 2). Background kaca dock mengecil/membesar mengikuti jumlah ikon di dalamnya.
- **Tema Liquid Glass.** Dock, folder, kartu App Library, kolom pencarian, menu, alert, tombol, dan indikator halaman memakai
  kaca yang membiaskan wallpaper (refraksi + sedikit pemisahan warna di tepi, Android 13+), kilau di bagian atas, dan tepi
  spekular. Blur, refraksi, frost/tint, dan kilau bisa diatur 0–200% di *Settings → Liquid Glass*. Android 14+ hanya mengizinkan aplikasi membaca wallpaper dengan izin *All files access*
  (*Settings → Allow Wallpaper Access*); tanpa izin itu kaca tampil sebagai frosted glass.
- Mode edit (jiggle): tekan lama ruang kosong atau pilih *Edit Home Screen*; seret ikon untuk menyusun ulang, pindah halaman
  di tepi layar, atau masukkan ke dock (maks. 4). Tombol "−" menghapus dari Home Screen (app tetap ada di App Library).
- Menu konteks gaya iOS saat ikon ditekan lama, swipe ke bawah untuk pencarian, swipe ke bawah dari pojok atas
  kiri/kanan untuk notifikasi / quick settings.
- Mendukung Work Profile.

## Build

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (ditandatangani dengan debug key, bisa langsung di-install).
GitHub Actions (`.github/workflows/build.yml`) juga membangun APK di setiap push; unduh dari tab *Actions → Artifacts*.

Setelah install, tekan tombol Home dan pilih **iOS Launcher** sebagai aplikasi Home default
(atau buka *Launcher Settings → Set as Default Home App*).

## Catatan

- Blur latar App Library memakai *cross-window blur* Android. Jika perangkat mematikannya (mis. hemat baterai),
  launcher memakai latar gelap transparan sebagai gantinya.
- Font SF Pro tidak disertakan karena lisensi Apple; launcher memakai font sistem.
