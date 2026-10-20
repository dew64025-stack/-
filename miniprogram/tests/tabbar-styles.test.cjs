const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { test } = require('node:test');
const root = path.resolve(__dirname, '..');
const compiler = process.env.WXSS_COMPILER;
for (const name of ['home', 'opportunity', 'service', 'mutual', 'profile']) {
  test(name + ' 页面底部留白样式不依赖额外公共文件也能完成官方编译', { skip: !compiler }, () => {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'czt-wxss-test-'));
    try {
      const result = spawnSync(compiler, ['-lc', '-o', path.join(temp, 'compiled.js'), './pages/' + name + '/' + name + '.wxss'], { cwd: root, encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr || result.stdout || String(result.error));
      assert.ok(fs.statSync(path.join(temp, 'compiled.js')).size > 0);
    } finally {
      fs.rmSync(temp, { recursive: true, force: true });
    }
  });
}
