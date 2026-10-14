# 本机部署准备（开发中）

当前后端还在逐模块开发，本文件不是“已经可以一键上线”的说明。最终Compose和启动脚本由部署任务交付。

## 先准备什么

用户已选择本机Docker运行Redis、私密文件存储和病毒扫描。MySQL仍支持原先另一台电脑上的服务器；不改现有数据库、不占用本机已有MySQL84的3306端口。

先按[Docker官方Windows安装说明](https://docs.docker.com/desktop/setup/install/windows-install/)安装并启动Docker Desktop。推荐WSL2后端；是否安装Windows组件、是否重启由电脑使用者确认。不要为了跑演示关闭安全软件、把数据库开放给全网，或把Docker管理接口暴露出去。

安装后可在PowerShell验证：

```powershell
docker version
docker compose version
```

`docker version`必须同时出现Client和Server；只有命令行程序存在不代表引擎已经可用。Redis、扫描和存储会使用独立容器、数据卷和本机绑定端口，具体端口在最终脚本中检查冲突后确定。

## 安装提示缺少Virtual Machine Platform时

用户2026-10-14提供的安装截图明确提示“Virtual Machine Platform”功能未启用，按用户安装模式不能自行提权启用。这是Windows前置功能问题，不是项目代码、MySQL或Redis错误。

由电脑使用者手动打开“终端（管理员）”或“PowerShell（管理员）”，执行：

```powershell
wsl --install --no-distribution
```

成功后保存未保存的文件、重启电脑，再运行Docker Desktop安装程序并使用WSL2后端。该参数只安装WSL，不额外安装Ubuntu，见[微软WSL命令说明](https://learn.microsoft.com/en-us/windows/wsl/basic-commands)。命令若失败，先保留具体错误再诊断，不连续堆叠系统修改；助手没有自动执行此命令、安装系统功能或重启电脑。

安装完成后验证`wsl --version`与`docker version`；WSL版本须满足[Docker官方系统要求](https://docs.docker.com/desktop/setup/install/windows-install/)。如果出现新的虚拟化/网络错误，应针对新错误核查，而不是删除已有WSL发行版、Docker数据卷或数据库文件。

若管理员窗口仍显示“已禁止(403)”，只证明下载请求被拒绝，不能仅凭这一行确定是商店、代理还是上游限制。可按微软支持的另一下载路径尝试：

```powershell
wsl --install --web-download --no-distribution
```

若仍失败，保留错误，改用[Docker官方说明中的WSL MSI安装方法](https://docs.docker.com/desktop/setup/install/windows-install/#option-2-install-wsl-via-the-msi-package)；不关闭证书/安全检查，也不从不明镜像下载安装包。该替代命令和实际成功状态均由用户操作确认，助手未执行。

## 文件存储的维护风险

2026-10-14核查时，[MinIO社区服务端仓库](https://github.com/minio/minio)已归档并明确停止维护。原规划允许MinIO或兼容S3存储，后端会通过统一S3适配层连接，避免业务代码绑定某个品牌。

仍在更新的客户端SDK不代表旧服务端镜像可以安全上线。最终本机存储提供器、固定版本、镜像来源和扫描结果需要在部署验收中明确；暂不把旧MinIO社区镜像作为上线默认，也不擅自改成收费云存储。

## 配置与真实验收

真实微信AppSecret、MySQL密码、服务密码和加密密钥只放在本地受保护配置/密钥管理中，不发到聊天、不提交GitHub、不打印日志。当前没有要求提供这些真实值。

数据库联调使用独立测试库及专用账号。应用账号和迁移账号分开，测试和迁移前备份；不能拿现有业务库当作可清空的临时库。远程连接的内网/VPN、访问白名单和加密连接按原规划的安全要求配置。

H2、模拟微信和内存Redis测试只证明隔离测试范围。真实微信、MySQL、Redis、文件扫描、预约压力和备份恢复须另行执行并记录结果，未执行的项目不算通过。
