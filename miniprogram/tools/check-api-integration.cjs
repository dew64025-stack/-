const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const config = JSON.parse(read('app.json'));
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(x => x.isDirectory() ? walk(path.join(dir, x.name)) : [path.join(dir, x.name)]); }
global.wx = { getStorageSync() { return null; } };
let pageCount = 0;
for (const route of config.pages) {
  for (const ext of ['js', 'wxml', 'json']) assert(fs.existsSync(path.join(root, route + '.' + ext)), route + '.' + ext + ' missing');
  JSON.parse(read(route + '.json'));
  new vm.Script(read(route + '.js'), { filename: route + '.js' });
  let definition;
  global.Page = value => { definition = value; };
  const file = path.join(root, route + '.js');
  delete require.cache[require.resolve(file)]; require(file);
  assert(definition, route + ' does not register Page');
  const wxml = read(route + '.wxml');
  for (const m of wxml.matchAll(/\b(?:bind|catch):?[a-z]+="([A-Za-z_$][\w$]*)"/g)) assert.equal(typeof definition[m[1]], 'function', route + ': missing event ' + m[1]);
  for (const m of wxml.matchAll(/\bsrc="([^"]+)"/g)) {
    if (m[1].includes('{{') || /^https?:/.test(m[1])) continue;
    const asset = m[1].startsWith('/') ? path.join(root, m[1]) : path.resolve(path.dirname(file), m[1]);
    assert(fs.existsSync(asset), route + ': missing asset ' + m[1]);
  }
  pageCount++;
}
for (const dir of ['utils', 'config']) for (const file of walk(path.join(root, dir)).filter(x => x.endsWith('.js'))) new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file });
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'czt-api-compile-'));
const compiler = 'D:/微信开发者工具/微信web开发者工具/resources/app.asar.unpacked/node_modules/wcc-exec/';
const files = ['pages', 'templates', 'custom-tab-bar', 'styles'].filter(x => fs.existsSync(path.join(root, x))).flatMap(x => walk(path.join(root, x))).map(x => path.relative(root, x).replaceAll('\\', '/'));
for (const [exe, ext, flags] of [['wcc.exe', '.wxml', []], ['wcsc.exe', '.wxss', ['-lc']]]) {
  const inputs = files.filter(x => x.endsWith(ext)); if (ext === '.wxss') inputs.unshift('app.wxss');
  const result = cp.spawnSync(path.join(compiler, exe), [...flags, ...inputs, '-o', path.join(output, exe + '.out')], { cwd: root, encoding: 'utf8', windowsHide: true });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, exe + '\n' + result.stderr + result.stdout);
}
console.log('PASS: ' + pageCount + ' pages; JS/JSON, event handlers, local assets, full WXML and WXSS compilation.');
