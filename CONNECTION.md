# 数据库连接信息

本机演示数据库已按 `DATABASE.md` 完成初始化（74 张表 + 演示种子 + 三账号白名单）。

## 连接参数

| 项目 | 值 |
| --- | --- |
| 主机 | `127.0.0.1`（本机） |
| 端口 | `3306` |
| 数据库 | `caizhitong` |
| 字符集 / 排序 | `utf8mb4` / `utf8mb4_unicode_ci` |
| MySQL 版本 | 8.0.26 |

## 账号（密码不提交，见本机 `.env`）

| 账号 | 用途 | 权限 |
| --- | --- | --- |
| `caizhitong_app` | 业务（后端运行时） | 71 表增删改查 + `pseudo_identities` 仅 INSERT + 审计仅追加 |
| `caizhitong_migrator` | 迁移（Flyway） | DDL + REFERENCES |
| `caizhitong_investigator` | 安全调查 | 只读受限映射/审计 + 调查记录追加 |

## 后端 Spring Boot 配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/caizhitong?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: caizhitong_app
    password: ${MYSQL_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration
    user: ${FLYWAY_USER}
    password: ${FLYWAY_PASSWORD}
```

环境变量（本机 `.env`，**不要提交**）：

```dotenv
MYSQL_PASSWORD=<业务账号密码>
FLYWAY_USER=caizhitong_migrator
FLYWAY_PASSWORD=<迁移账号密码>
```

## 命令行连接

```powershell
mysql -h 127.0.0.1 -P 3306 -u caizhitong_app -p caizhitong
```

> 账号主机为 `localhost`（本机 127.0.0.1 连接解析为 localhost）。若后端部署在另一台电脑，需按那台电脑的来源 IP 重建账号授权。密码、AppSecret、AI 密钥一律进本机密钥配置，不进仓库。
