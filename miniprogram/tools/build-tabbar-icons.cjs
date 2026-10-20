// Rasterize the local vector sources at 3x for native WeChat image compatibility.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require(process.env.SHARP_MODULE || 'sharp');
const root = path.resolve(__dirname, '../assets/tabbar');
(async () => {
  for (const name of ['home', 'opportunity', 'service', 'mutual', 'profile']) {
    const source = fs.readFileSync(path.join(root, name + '.svg'), 'utf8');
    for (const active of [false, true]) {
      const svg = active ? source.replaceAll('#4a5568', '#ffffff') : source;
      await sharp(Buffer.from(svg)).resize(72, 72).png()
        .toFile(path.join(root, name + (active ? '-active' : '') + '.png'));
    }
  }
  console.log('Generated 10 local tab icons from SVG sources.');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
