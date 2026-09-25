// A PNG writer with no dependencies: 8-bit RGB, zlib "stored" (uncompressed)
// blocks, CRC32 and Adler32 by hand. Big files, but nothing to install.
#ifndef HUD_PNG_WRITE_H
#define HUD_PNG_WRITE_H

#include <cstdint>
#include <cstdio>
#include <vector>

static uint32_t pngCrc_(const uint8_t* d, size_t n, uint32_t c) {
  static uint32_t table[256];
  static bool ready = false;
  if (!ready) {
    for (uint32_t i = 0; i < 256; i++) {
      uint32_t k = i;
      for (int b = 0; b < 8; b++) k = (k & 1) ? 0xEDB88320u ^ (k >> 1) : k >> 1;
      table[i] = k;
    }
    ready = true;
  }
  for (size_t i = 0; i < n; i++) c = table[(c ^ d[i]) & 0xFF] ^ (c >> 8);
  return c;
}

static void pngBe32_(std::vector<uint8_t>& v, uint32_t x) {
  v.push_back(x >> 24); v.push_back(x >> 16); v.push_back(x >> 8); v.push_back(x);
}

static void pngChunk_(std::vector<uint8_t>& out, const char* type, const std::vector<uint8_t>& data) {
  pngBe32_(out, (uint32_t)data.size());
  std::vector<uint8_t> td(type, type + 4);
  td.insert(td.end(), data.begin(), data.end());
  out.insert(out.end(), td.begin(), td.end());
  pngBe32_(out, pngCrc_(td.data(), td.size(), 0xFFFFFFFFu) ^ 0xFFFFFFFFu);
}

/** Writes an RGB565 framebuffer as a PNG. Returns false if the file cannot be written. */
static bool writePng565(const char* path, const uint16_t* px, int w, int h) {
  std::vector<uint8_t> raw;
  raw.reserve((size_t)h * (1 + (size_t)w * 3));
  for (int y = 0; y < h; y++) {
    raw.push_back(0);                                   // filter: none
    for (int x = 0; x < w; x++) {
      const uint16_t c = px[y * w + x];
      const uint8_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
      raw.push_back((uint8_t)((r << 3) | (r >> 2)));
      raw.push_back((uint8_t)((g << 2) | (g >> 4)));
      raw.push_back((uint8_t)((b << 3) | (b >> 2)));
    }
  }
  std::vector<uint8_t> z = {0x78, 0x01};                // zlib header, no dictionary
  size_t pos = 0;
  do {
    const size_t n = (raw.size() - pos > 65535) ? 65535 : raw.size() - pos;
    z.push_back(pos + n == raw.size() ? 1 : 0);         // BFINAL, BTYPE = stored
    z.push_back(n & 0xFF); z.push_back((n >> 8) & 0xFF);
    z.push_back(~n & 0xFF); z.push_back((~n >> 8) & 0xFF);
    z.insert(z.end(), raw.begin() + pos, raw.begin() + pos + n);
    pos += n;
  } while (pos < raw.size());
  uint32_t a = 1, b = 0;
  for (uint8_t d : raw) { a = (a + d) % 65521; b = (b + a) % 65521; }
  pngBe32_(z, (b << 16) | a);

  std::vector<uint8_t> out = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  std::vector<uint8_t> ihdr;
  pngBe32_(ihdr, (uint32_t)w); pngBe32_(ihdr, (uint32_t)h);
  ihdr.push_back(8); ihdr.push_back(2);                 // 8-bit, truecolour
  ihdr.push_back(0); ihdr.push_back(0); ihdr.push_back(0);
  pngChunk_(out, "IHDR", ihdr);
  pngChunk_(out, "IDAT", z);
  pngChunk_(out, "IEND", {});

  FILE* f = fopen(path, "wb");
  if (!f) return false;
  const bool ok = fwrite(out.data(), 1, out.size(), f) == out.size();
  return fclose(f) == 0 && ok;
}

#endif  // HUD_PNG_WRITE_H
