#!/usr/bin/env python3
"""Add live backend data panels to the home, opportunity and mutual pages.

Run after tools/convert_prototypes.py:
    python tools/convert_prototypes.py
    python tools/apply_api_panels.py

Only public endpoints are used, so this works before WeChat login is configured.
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "miniprogram-demo"
MARK = 'class="api-panel"'

COMMON_CSS = r'''
.api-panel {
  position: relative;
  z-index: 10;
  margin: calc(84px + env(safe-area-inset-top, 0px)) 12px 12px;
  padding: 14px;
  background: #ffffff;
  border-radius: 14px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, .06);
}
.api-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.api-title { display: block; font-size: 16px; line-height: 22px; font-weight: 700; color: #00324b; }
.api-sub { display: block; font-size: 10px; line-height: 14px; color: #72787e; margin-top: 2px; }
.api-refresh { padding: 4px 10px; border-radius: 999px; background: #e7eeff; color: #00324b; font-size: 11px; }
.api-state { padding: 10px 0; font-size: 12px; color: #72787e; }
.api-error { color: #ba1a1a; }
.api-block { margin-top: 12px; }
.api-block-title { display: block; font-size: 13px; font-weight: 600; color: #121c2c; margin-bottom: 6px; }
.api-item { padding: 10px 11px; border-radius: 10px; background: #f0f3ff; margin-bottom: 8px; }
.api-item:last-child { margin-bottom: 0; }
.api-item-title { display: block; font-size: 14px; line-height: 20px; font-weight: 600; color: #121c2c; }
.api-item-meta { display: block; font-size: 11px; line-height: 16px; color: #72787e; margin-top: 3px; }
.api-empty { padding: 8px 0; font-size: 12px; color: #72787e; }
'''


def panel_open(title, sub):
    return f'''<view class="api-panel">
  <view class="api-head">
    <view>
      <text class="api-title">{title}</text>
      <text class="api-sub">{sub}</text>
    </view>
    <text class="api-refresh" bindtap="reload">刷新</text>
  </view>
  <view wx:if="{{{{loading}}}}" class="api-state">加载中…</view>
  <view wx:elif="{{{{error}}}}" class="api-state api-error" bindtap="reload">加载失败：{{{{error}}}} · 点此重试</view>
  <block wx:else>
'''


PANEL_CLOSE = '''  </block>
</view>
'''

HOME_WXML = panel_open("后端实时数据", "公开接口 · 演示校园 · /home/overview") + '''
    <view wx:if="{{deadlines.length}}" class="api-block">
      <text class="api-block-title">截止事项（{{deadlines.length}}）</text>
      <view wx:for="{{deadlines}}" wx:key="id" class="api-item" bindtap="showDeadline" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.category || '机会'}} · 截止 {{item.deadlineText || '未设置'}}</text>
      </view>
    </view>
    <view wx:if="{{recommendations.length}}" class="api-block">
      <text class="api-block-title">推荐机会</text>
      <view wx:for="{{recommendations}}" wx:key="id" class="api-item" bindtap="showRecommendation" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.category || '机会'}} · {{item.sourceName || item.sourceType}}</text>
      </view>
    </view>
    <view wx:if="{{quickOpportunities.length}}" class="api-block">
      <text class="api-block-title">机会速览</text>
      <view wx:for="{{quickOpportunities}}" wx:key="id" class="api-item" bindtap="showQuickOpportunity" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.categoryLabel}} · 截止 {{item.deadlineText || '未设置'}}</text>
      </view>
    </view>
    <view wx:if="{{hotIssues.length}}" class="api-block">
      <text class="api-block-title">热点问题</text>
      <view wx:for="{{hotIssues}}" wx:key="id" class="api-item" bindtap="showIssue" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.locationName || '校园公共区域'}}</text>
      </view>
    </view>
    <view wx:if="{{nearbyPosts.length}}" class="api-block">
      <text class="api-block-title">附近互助</text>
      <view wx:for="{{nearbyPosts}}" wx:key="id" class="api-item" bindtap="showNearby" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.summary || ''}}</text>
      </view>
    </view>
    <view wx:if="{{!deadlines.length && !recommendations.length && !quickOpportunities.length && !hotIssues.length && !nearbyPosts.length}}" class="api-empty">当前校园暂无可展示数据</view>
''' + PANEL_CLOSE

OPPORTUNITY_WXML = panel_open("后端实时数据", "公开接口 · 演示校园 · /opportunities") + '''
    <view class="api-block">
      <text class="api-block-title">机会列表（{{opportunities.length}}）</text>
      <view wx:for="{{opportunities}}" wx:key="id" class="api-item" bindtap="showOpportunity" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.categoryLabel}} · {{item.organizer || '来源待定'}} · 截止 {{item.deadlineText || '未设置'}}</text>
      </view>
      <view wx:if="{{!opportunities.length}}" class="api-empty">暂无可展示的机会</view>
    </view>
''' + PANEL_CLOSE

MUTUAL_WXML = panel_open("后端实时数据", "公开接口 · 演示校园 · /mutual-aid/posts") + '''
    <view class="api-block">
      <text class="api-block-title">互助列表（{{posts.length}}）</text>
      <view wx:for="{{posts}}" wx:key="id" class="api-item" bindtap="showPost" data-index="{{index}}">
        <text class="api-item-title">{{item.title}}</text>
        <text class="api-item-meta">{{item.categoryLabel}} · {{item.exchangeArea || '地点待定'}} · {{item.priceText}}</text>
      </view>
      <view wx:if="{{!posts.length}}" class="api-empty">暂无可展示的互助信息</view>
    </view>
''' + PANEL_CLOSE

HOME_JS = r'''const { request } = require('../../utils/request');
const { formatDateTime, opportunityCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    deadlines: [],
    recommendations: [],
    quickOpportunities: [],
    hotIssues: [],
    nearbyPosts: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return Promise.all([
      request('/home/overview?campusCode=demo-univ'),
      request('/opportunities?limit=3&campusCode=demo-univ')
    ]).then(([data, oppPage]) => {
      const map = (items) => (items || []).map((item) => Object.assign({}, item, {
        deadlineText: formatDateTime(item.deadline),
        updatedText: formatDateTime(item.updatedAt)
      }));
      const quickOpportunities = (oppPage.items || []).map((item) => Object.assign({}, item, {
        categoryLabel: opportunityCategory(item.category),
        deadlineText: formatDateTime(item.deadline)
      }));
      this.setData({
        loading: false,
        deadlines: map(data.deadlines),
        recommendations: map(data.recommendations),
        quickOpportunities,
        hotIssues: map(data.hotIssues),
        nearbyPosts: map(data.nearbyPosts)
      });
    }).catch((e) => this.setData({
      loading: false,
      error: (e.error && e.error.code) || 'NETWORK_ERROR'
    }));
  },

  showDeadline(e) {
    const item = this.data.deadlines[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'), showCancel: false });
  },

  showRecommendation(e) {
    const item = this.data.recommendations[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n来源：' + (item.sourceName || item.sourceType || '未知'), showCancel: false });
  },

  showQuickOpportunity(e) {
    const item = this.data.quickOpportunities[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.organizer ? '主办：' + item.organizer + '\n' : '') + (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'), showCancel: false });
  },

  showIssue(e) {
    const item = this.data.hotIssues[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: (item.summary || '') + '\n位置：' + (item.locationName || '校园公共区域'), showCancel: false });
  },

  showNearby(e) {
    const item = this.data.nearbyPosts[e.currentTarget.dataset.index];
    wx.showModal({ title: item.title, content: item.summary || '', showCancel: false });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
'''

OPPORTUNITY_JS = r'''const { request } = require('../../utils/request');
const { formatDateTime, opportunityCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    opportunities: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return request('/opportunities?limit=20&campusCode=demo-univ')
      .then((page) => {
        const opportunities = (page.items || []).map((item) => Object.assign({}, item, {
          categoryLabel: opportunityCategory(item.category),
          deadlineText: formatDateTime(item.deadline)
        }));
        this.setData({ loading: false, opportunities });
      })
      .catch((e) => this.setData({
        loading: false,
        error: (e.error && e.error.code) || 'NETWORK_ERROR'
      }));
  },

  showOpportunity(e) {
    const item = this.data.opportunities[e.currentTarget.dataset.index];
    wx.showModal({
      title: item.title,
      content: (item.organizer ? '主办：' + item.organizer + '\n' : '') +
        (item.summary || '') + '\n截止：' + (item.deadlineText || '未设置'),
      showCancel: false
    });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
'''

MUTUAL_JS = r'''const { request } = require('../../utils/request');
const { formatDateTime, mutualCategory } = require('../../utils/format');

Page({
  data: {
    loading: true,
    error: '',
    posts: []
  },

  onLoad() {
    this.reload();
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh(), () => wx.stopPullDownRefresh());
  },

  reload() {
    this.setData({ loading: true, error: '' });
    return request('/mutual-aid/posts?limit=20&campusCode=demo-univ')
      .then((page) => {
        const posts = (page.items || []).map((item) => Object.assign({}, item, {
          categoryLabel: mutualCategory(item.category),
          priceText: item.price ? '¥' + item.price : '免费/面议',
          createdText: formatDateTime(item.createdAt)
        }));
        this.setData({ loading: false, posts });
      })
      .catch((e) => this.setData({
        loading: false,
        error: (e.error && e.error.code) || 'NETWORK_ERROR'
      }));
  },

  showPost(e) {
    const item = this.data.posts[e.currentTarget.dataset.index];
    wx.showModal({
      title: item.title,
      content: (item.content || '') + '\n地点：' + (item.exchangeArea || '待定') +
        '\n价格：' + item.priceText + '\n发布：' + (item.createdText || ''),
      showCancel: false
    });
  },

  protoBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/pages/index/index' });
  }
});
'''

FORMAT_JS = r'''function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

function formatDateTime(value) {
  if (!value) return '';
  const d = new Date(value);
  if (isNaN(d.getTime())) return String(value);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function opportunityCategory(value) {
  return ({
    scholarship: '奖学金',
    competition: '学科竞赛',
    research: '科研研习',
    internship: '实习',
    volunteer: '志愿'
  })[value] || (value || '机会');
}

function mutualCategory(value) {
  return ({
    lost_found: '失物招领',
    secondhand: '二手闲置',
    borrow: '借用共享',
    textbook: '教材循环',
    skill: '技能互助',
    team: '组队搭子'
  })[value] || (value || '互助');
}

module.exports = { formatDateTime, opportunityCategory, mutualCategory };
'''


def patch(slug, panel, js):
    page = OUT / "pages" / slug
    wxml_path = page / f"{slug}.wxml"
    wxml = wxml_path.read_text(encoding="utf-8")
    if MARK not in wxml:
        wxml = panel + "\n" + wxml
    wxml_path.write_text(wxml, encoding="utf-8")
    (page / f"{slug}.js").write_text(js, encoding="utf-8")
    (page / f"{slug}.wxss").write_text(COMMON_CSS, encoding="utf-8")
    meta = json.loads((page / f"{slug}.json").read_text(encoding="utf-8"))
    meta["enablePullDownRefresh"] = True
    (page / f"{slug}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    (OUT / "utils").mkdir(parents=True, exist_ok=True)
    (OUT / "utils" / "format.js").write_text(FORMAT_JS, encoding="utf-8")
    patch("home", HOME_WXML, HOME_JS)
    patch("opportunity", OPPORTUNITY_WXML, OPPORTUNITY_JS)
    patch("mutual", MUTUAL_WXML, MUTUAL_JS)
    print("API panels applied to home / opportunity / mutual")


if __name__ == "__main__":
    main()
