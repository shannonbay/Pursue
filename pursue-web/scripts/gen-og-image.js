/**
 * Writes a minimal 1x1 PNG placeholder to public/og-image.png and public/apple-touch-icon.png.
 * Replace with real 1200x630 (OG) and 180x180 (apple) assets later.
 */
import { writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const publicDir = join(__dirname, '..', 'public');

// Minimal 1x1 transparent PNG (67 bytes)
const minimalPngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const buf = Buffer.from(minimalPngB64, 'base64');

writeFileSync(join(publicDir, 'og-image.png'), buf);
writeFileSync(join(publicDir, 'apple-touch-icon.png'), buf);
console.log('Wrote public/og-image.png and public/apple-touch-icon.png');
