# Changelog

## v1.0.1

- App Library: kotak search ikut bergulir ke atas bersama kartu saat di-scroll.
- App Library & pencarian: menu tekan-lama selalu menampilkan **Add to Home Screen**. Bila aplikasi sudah ada di
  Home Screen, launcher memberi tahu letaknya (halaman, dock, atau folder) alih-alih membuat duplikat.
- Ikon kecil di klaster 2×2 kartu App Library kini bisa ditekan-lama untuk membuka menunya.

## v1.0.0 — Rilis pertama

Launcher bergaya iOS untuk Android 17 (berjalan di Android 12 ke atas).

### Home Screen
- Bentuk ikon 1:1 dengan iOS (*continuous-corner squircle*, radius 22,37%) untuk semua ikon, dock, folder, widget,
  kartu App Library, menu, dan alert.
- Grid baris × kolom bisa diatur 3–8 (sampai 8 × 8); ikon dan nama otomatis menyesuaikan pada grid yang padat.
- **Free Placement**: ikon tetap di petak tempat dilepas, petak kosong dibiarkan kosong; melepas di atas ikon lain menukar posisi.
- Ukuran ikon Home Screen dan Dock diatur terpisah (50–130%), opsi sembunyikan nama ikon.
- Mode edit (jiggle): seret untuk menyusun ulang, pindah halaman di tepi layar, tombol "−" untuk menghapus dari Home Screen.
  Tombol Settings, + (widget), dan Done sebaris dengan indikator status.
- Menu tekan-lama gaya iOS (Edit Home Screen, Edit Icon, App Info, Remove App).
- Fullscreen: status bar disembunyikan; Page 1 menampilkan jam digital dan indikator gabungan baterai / Wi-Fi / sinyal
  di pojok kanan atas (diadaptasi dari Duo Launcher, lisensi MIT).

### Folder & Widget
- Folder: tahan ikon di atas ikon lain untuk membuat folder, nama otomatis sesuai kategori, bisa diganti.
- Folder bisa diubah ukurannya per petak (pegangan kiri/kanan/bawah atau menu 1×1, 2×2, lebar penuh); folder besar
  menampilkan aplikasinya langsung.
- Folder di dock: satu slot dengan pratinjau 2×2; folder bisa dibuat langsung di dock.
- Widget: galeri widget dengan pratinjau, mengikuti grid ikon dengan sudut iOS, bisa diseret, diubah ukurannya,
  dikonfigurasi ulang, dan dihapus.

### Dock
- Dock kaca yang lebarnya menyesuaikan jumlah ikon (dengan animasi).
- Dock bisa dimatikan, atau disembunyikan hanya di Page 1 (Page 1 lalu mendapat satu baris ikon tambahan).
- Tanpa dock: titik halaman polos dan tombol search kaca bulat di kanan bawah (ikon berubah hitam di dark mode).

### Ikon
- Membaca icon pack yang terinstal (format ADW / Nova / Apex / GO / Lawnchair, termasuk iconback / iconmask / iconupon).
- Custom icon per aplikasi: gambar sendiri dari galeri atau ikon apa pun dari icon pack yang terinstal.

### App Library & Pencarian
- App Library dengan kategori otomatis (Suggestions, Recently Added, Social, Productivity & Finance, Utilities,
  Entertainment, dll.) dengan proporsi kartu seperti iOS; kategori bisa dibuka penuh.
- Pencarian dengan carousel ubin 3D: grup 1 ubin besar + 2 ubin kecil yang tebal dan mengkilap, berputar dalam perspektif,
  strip A–Z, pencarian langsung, dan Suggestions yang menghilang mulus saat keyboard muncul.
- Saat pencarian, menu, atau folder terbuka, home screen di belakangnya di-blur, sedikit mengecil, dan meredup.

### Liquid Glass
- Material kaca di dock, folder, App Library, menu, alert, tombol, dan indikator halaman: blur wallpaper, refraksi di tepi,
  kilau, dan tepi spekular yang mengikuti kemiringan HP.
- Blur, refraksi, frost/tint, dan kilau bisa diatur 0–200%.
- Mode frosted otomatis bila wallpaper tidak bisa dibaca (izin belum diberikan atau live wallpaper); Settings menampilkan
  status sumber kaca.

### Animasi
- Animasi buka kunci gaya iOS 26: ikon melayang masuk dari tepi layar, berjenjang per baris, dengan pantulan pegas.
- Paralaks kemiringan HP.
- Aplikasi dibuka dengan zoom dari ikonnya.
- Animasi kembali ke ikon saat menutup aplikasi tersedia sebagai opsi eksperimental (nonaktif secara default).

### Lainnya
- Perekam crash: laporan crash terakhir bisa disalin atau dibagikan dari Settings.
- Mendukung Work Profile.
- APK ditandatangani dengan kunci sideload yang sama di setiap build, sehingga update bisa dipasang langsung.

---

## Riwayat pengembangan (build pra-rilis)

Build uji yang dibagikan selama pengembangan, sebelum rilis v1.0.0:

1. Launcher dasar: Home Screen, App Library, icon pack, squircle iOS, animasi buka kunci.
2. Ukuran ikon Home Screen / Dock terpisah, sembunyikan nama ikon, indikator status dinaikkan.
3. Jam digital di atas indikator status, indikator diperkecil.
4. Ukuran jam disesuaikan (70%).
5. Folder yang bisa diubah ukurannya, grid sampai 8×8, opsi dock, dock yang menyesuaikan lebar, tema Liquid Glass.
6. Mode tanpa dock: titik halaman polos dan tombol search kaca bulat.
7. Free Placement dan custom icon per aplikasi.
8. Opsi sembunyikan dock hanya di Page 1.
9. Page 1 memakai ruang dock untuk satu baris ikon tambahan.
10. Pengaturan Liquid Glass (blur, refraksi, frost, kilau).
11. Animasi buka kunci gaya iOS 26, paralaks, kilau kaca mengikuti kemiringan.
12. Tombol search muncul di Page 1 saat dock disembunyikan di sana.
13. Posisi titik halaman dan tombol search disesuaikan.
14. Widget; tombol mode edit sejajar dengan padding ikon.
15. Pencarian berbentuk carousel ikon.
16. Pencarian berbentuk drum 3D dua baris.
17. Pencarian dengan grup ubin 3D (1 besar + 2 kecil); Suggestions mengikuti keyboard.
18. Animasi kembali ke ikon saat menutup aplikasi.
19. Tombol mode edit sebaris dengan indikator status; workflow rilis GitHub.
20. Home screen di-blur saat overlay terbuka; latar pencarian berlapis.
21. Slider Blur juga mengatur kaca frosted; status sumber kaca di Settings.
22–25. Percobaan beberapa animasi buka aplikasi; dikembalikan ke zoom dari ikon.
26. Penyebab glitch splash ditemukan: animasi kembali ke ikon dijadikan opsi eksperimental (nonaktif).
27. App Library didesain ulang dengan proporsi iOS; kotak search turun 14pt.
28. Folder di dock.
29. Perbaikan crash saat menyeret folder; perekam crash.

---
© 2026 lutfananas. All rights reserved. Lihat LICENSE di repositori.
