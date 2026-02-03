/**
 * Writes a minimal 16x16 ICO (blue square) to public/favicon.ico.
 * ICO: ICONDIR(6) + ICONDIRENTRY(16) + BITMAPINFOHEADER(40) + pixels(1024) + AND mask(64)
 */
import { writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const out = join(__dirname, '..', 'public', 'favicon.ico');

const headerSize = 6 + 16 + 40;
const pixelBytes = 16 * 16 * 4; // BGRA
const andMaskRow = Math.ceil(16 / 32) * 4; // 4 bytes per row for 1bpp
const andMaskSize = andMaskRow * 16;
const imageSize = 40 + pixelBytes + andMaskSize;
const dataOffset = 6 + 16;

const buf = Buffer.alloc(6 + 16 + 40 + pixelBytes + andMaskSize);
let o = 0;

// ICONDIR
buf.writeUInt16LE(0, o); o += 2;
buf.writeUInt16LE(1, o); o += 2;
buf.writeUInt16LE(1, o); o += 2;

// ICONDIRENTRY: 16x16 (height in entry is 32 for image+mask)
buf[o++] = 16; buf[o++] = 32; buf[o++] = 0; buf[o++] = 0;
buf.writeUInt16LE(1, o); o += 2;
buf.writeUInt16LE(32, o); o += 2;
buf.writeUInt32LE(imageSize, o); o += 4;
buf.writeUInt32LE(dataOffset, o); o += 4;

// BITMAPINFOHEADER (40 bytes)
buf.writeUInt32LE(40, o); o += 4;
buf.writeInt32LE(16, o); o += 4;
buf.writeInt32LE(32, o); o += 4; // height * 2 for image + mask
buf.writeUInt16LE(1, o); o += 2;
buf.writeUInt16LE(32, o); o += 2;
buf.writeUInt32LE(0, o); o += 4;
buf.writeUInt32LE(0, o); o += 4;
buf.writeInt32LE(0, o); o += 4;
buf.writeInt32LE(0, o); o += 4;
buf.writeUInt32LE(0, o); o += 4;
buf.writeUInt32LE(0, o); o += 4;

// Pixels: 16x16 BGRA, bottom-up. Blue (#1976D2) fill.
const r = 0x19, g = 0x76, b = 0xd2, a = 255;
for (let y = 15; y >= 0; y--) {
  for (let x = 0; x < 16; x++) {
    buf[o++] = b; buf[o++] = g; buf[o++] = r; buf[o++] = a;
  }
}

// AND mask: 0 = opaque (16 rows, 4 bytes each)
for (let i = 0; i < andMaskSize; i++) buf[o++] = 0;

writeFileSync(out, buf);
console.log('Wrote', out);
