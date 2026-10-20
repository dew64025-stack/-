Component({
  data: {
    selected: 0,
    list: [
      { pagePath: 'pages/home/home', text: '首页', iconPath: '/assets/tabbar/home.png', selectedIconPath: '/assets/tabbar/home-active.png' },
      { pagePath: 'pages/opportunity/opportunity', text: '机会', iconPath: '/assets/tabbar/opportunity.png', selectedIconPath: '/assets/tabbar/opportunity-active.png' },
      { pagePath: 'pages/service/service', text: '服务', iconPath: '/assets/tabbar/service.png', selectedIconPath: '/assets/tabbar/service-active.png' },
      { pagePath: 'pages/mutual/mutual', text: '互助', iconPath: '/assets/tabbar/mutual.png', selectedIconPath: '/assets/tabbar/mutual-active.png' },
      { pagePath: 'pages/profile/profile', text: '我的', iconPath: '/assets/tabbar/profile.png', selectedIconPath: '/assets/tabbar/profile-active.png' }
    ]
  },

  lifetimes: {
    attached() {
      // Each tab page owns its own component, including pages opened directly.
      const pages = getCurrentPages();
      const current = pages[pages.length - 1];
      if (!current) return;
      const selected = this.data.list.findIndex(item => item.pagePath === current.route);
      if (selected !== -1) this.setData({ selected });
    }
  },

  methods: {
    onTabTap(e) {
      const value = e.currentTarget.dataset.index;
      if (typeof value !== 'number' && !(typeof value === 'string' && /^\d+$/.test(value))) return;
      const index = Number(value);
      if (!Number.isInteger(index) || index < 0 || index >= this.data.list.length) return;
      if (this._switching || index === this.data.selected) return;

      this._switching = true;
      wx.switchTab({
        url: '/' + this.data.list[index].pagePath,
        fail: () => {
          wx.showToast({ title: '页面切换失败，请重试', icon: 'none' });
        },
        complete: () => {
          this._switching = false;
        }
      });
    }
  }
});
