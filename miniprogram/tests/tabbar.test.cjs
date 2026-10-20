const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { test } = require('node:test');
const root = path.resolve(__dirname, '..');
const tabs = [
  ['pages/home/home', '首页'],
  ['pages/opportunity/opportunity', '机会'],
  ['pages/service/service', '服务'],
  ['pages/mutual/mutual', '互助'],
  ['pages/profile/profile', '我的']
];
function component(route = tabs[0][0]) {
  const file = path.join(root, 'custom-tab-bar/index.js');
  assert.ok(fs.existsSync(file), '自定义底部导航尚未实现');
  let definition;
  const calls = [], notices = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: value => { definition = value; },
    getCurrentPages: () => [{ route }],
    wx: { switchTab: options => calls.push(options), showToast: options => notices.push(options) }
  });
  const instance = {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(value) { Object.assign(this.data, value); }
  };
  for (const [name, method] of Object.entries(definition.methods)) instance[name] = method.bind(instance);
  definition.lifetimes.attached.call(instance);
  return { instance, calls, notices };
}
const event = index => ({ currentTarget: { dataset: { index } } });

test('微信导航配置启用自定义组件，保留五个原有目标页面', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8').replace(/^\uFEFF/, ''));
  assert.equal(app.tabBar.custom, true);
  assert.deepEqual(app.tabBar.list.map(item => [item.pagePath, item.text]), tabs);
});

for (const [index, [route, label]] of tabs.entries()) {
  test('从任意入口打开' + label + '，导航初次创建就正确高亮', () => {
    const { instance } = component(route);
    assert.equal(instance.data.selected, index);
  });
  test(label + '页面再次显示时同步导航，不重置页面业务数据', () => {
    let definition;
    const pageRequire = createRequire(path.join(root, route + '.js'));
    const realUi = pageRequire('../../utils/ui');
    vm.runInNewContext(fs.readFileSync(path.join(root, route + '.js'), 'utf8'), {
      Page: value => { definition = value; }, wx: {}, getApp: () => ({ globalData: {} }),
      require: name => name === '../../utils/ui' ? Object.assign({}, realUi, { define: value => { definition = value; } }) : pageRequire(name)
    });
    assert.equal(typeof definition.onShow, 'function', '页面需要同步导航选中态');
    const bar = { data: { selected: -1 }, setData(value) { Object.assign(this.data, value); } };
    const page = { data: { sentinel: '保留表单' }, getTabBar: () => bar, reload() {} };
    definition.onShow.call(page);
    assert.equal(bar.data.selected, index);
    assert.equal(page.data.sentinel, '保留表单');
    assert.doesNotThrow(() => definition.onShow.call({ getTabBar: () => undefined, reload() {} }));
    assert.doesNotThrow(() => definition.onShow.call({ reload() {} }));
  });
}

test('点击其他入口调用原来的 switchTab 路由，不提前伪造选中状态', () => {
  for (const [index, [route]] of tabs.entries()) {
    if (!index) continue;
    const { instance, calls } = component();
    instance.onTabTap(event(String(index)));
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/' + route);
    assert.equal(instance.data.selected, 0);
  }
});

test('当前入口、无效入口与跳转进行中的重复点击不会重复发起跳转', () => {
  const { instance, calls } = component();
  for (const value of [0, -1, 5, 1.5, '', 'bad', undefined, null]) instance.onTabTap(event(value));
  assert.equal(calls.length, 0);
  instance.onTabTap(event(1));
  instance.onTabTap(event(2));
  assert.equal(calls.length, 1);
  calls[0].complete();
  instance.onTabTap(event(2));
  assert.equal(calls.length, 2);
});

test('跳转失败保留当前选中项，提示错误并允许重新点击', () => {
  const { instance, calls, notices } = component();
  instance.onTabTap(event(1));
  calls[0].fail({ errMsg: 'switchTab:fail' });
  calls[0].complete();
  assert.equal(instance.data.selected, 0);
  assert.equal(notices.length, 1);
  assert.equal(notices[0].icon, 'none');
  instance.onTabTap(event(1));
  assert.equal(calls.length, 2);
});

test('导航图标在离线情况下全部可读取，无需外部字体或网络图片', () => {
  const { instance } = component();
  assert.deepEqual(instance.data.list.map(item => [item.pagePath, item.text]), tabs);
  for (const item of instance.data.list) {
    for (const key of ['iconPath', 'selectedIconPath']) {
      assert.ok(item[key].startsWith('/assets/tabbar/'));
      const icon = fs.readFileSync(path.join(root, item[key]));
      assert.equal(icon.subarray(1, 4).toString(), 'PNG');
    }
  }
});
