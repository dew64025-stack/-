# 材智通 · 数据字典

> 完整列级数据字典，共 74 张表。与 `sql/V1__`~`V6__` 建表脚本一一对应，是接口文档与前端开发的字段依据。
> 阅读时先看「通用字段」，各表不再重复解释通用字段的长说明，只标注取值。

## 一、通用字段

核心业务表反复出现的字段，含义统一如下：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED | 应用层生成的全局唯一 ID（雪花算法），非自增 |
| `campus_id` | BIGINT UNSIGNED | 校园隔离键，指向 `campuses.id`；单校首发，底层预留多校 |
| `status` | VARCHAR(32) | 业务状态，各表取值见本表说明 |
| `version` | INT UNSIGNED | 乐观锁版本，并发修改冲突返回 HTTP 409 |
| `created_by` / `updated_by` | BIGINT UNSIGNED | 创建人 / 更新人（`users.id`） |
| `created_at` / `updated_at` | DATETIME(3) | 创建 / 更新时间 |
| `deleted_at` | DATETIME(3) | 软删除时间，`NULL` 表示未删除 |
| `source_type` | VARCHAR(16) | 数据来源：`demo` 演示 / `manual` 人工 / `official` 官方 |

约束说明：

- 明确的父子关系均建 `FOREIGN KEY`；多类型引用（`target_type`＋`target_id`）由后端统一校验。
- 关键同校关系用复合外键 `(campus_id, user_id) → users(campus_id, id)` 约束；`users` 表有 `UNIQUE(campus_id, id)` 支撑。
- 评分、投票、价格、时间范围等用 `CHECK` 约束。
- 敏感数据：手机号/学号检索值采用带密钥 HMAC，密文保存；匿名身份映射仅安全调查账号可读；审计日志只追加。

---

## 二、校园与身份（V1，12 表）

### 2.1 campuses — 校园租户
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| name | VARCHAR(128) | 否 | — | 校园名称 |
| code | VARCHAR(64) | 否 | — | 校园编码（唯一） |
| status | VARCHAR(32) | 否 | active | 状态 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 2.2 campus_regions — 校园区域
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| parent_id | BIGINT UNSIGNED | 是 | — | 上级区域 |
| name | VARCHAR(128) | 否 | — | 区域名称 |
| type | VARCHAR(32) | 否 | — | building/area/floor |
| status | VARCHAR(32) | 否 | active | 状态 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 2.3 campus_organizations — 校园组织
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| name | VARCHAR(128) | 否 | — | 组织名称 |
| type | VARCHAR(32) | 否 | — | college/department/club |
| status | VARCHAR(32) | 否 | active | 状态 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 2.4 campus_configs — 校园配置
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| config_key | VARCHAR(128) | 否 | — | 配置键 |
| config_value | JSON | 否 | — | 配置值 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 2.5 users — 用户
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| nickname | VARCHAR(64) | 是 | — | 昵称 |
| avatar_url | VARCHAR(512) | 是 | — | 头像地址 |
| status | VARCHAR(32) | 否 | active | active/banned |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

> `UNIQUE(campus_id, id)` 供同校复合外键引用。

### 2.6 user_wechat_identities — 微信身份
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| app_id | VARCHAR(64) | 否 | — | 小程序 AppID |
| openid | VARCHAR(128) | 否 | — | openid（同一 AppID 内唯一） |
| unionid | VARCHAR(128) | 是 | — | unionid |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 2.7 user_phones — 手机号（密文）
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| phone_encrypted | VARBINARY(255) | 否 | — | 手机号密文 |
| phone_search | CHAR(64) | 否 | — | 带密钥 HMAC 检索值（唯一） |
| verified | TINYINT(1) | 否 | 0 | 是否已验证 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 2.8 student_verifications — 学生认证
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（复合 FK 保证同校） |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园 |
| student_id_encrypted | VARBINARY(255) | 否 | — | 学号密文 |
| student_id_search | CHAR(64) | 否 | — | 带密钥 HMAC 检索值 |
| status | VARCHAR(32) | 否 | pending | pending/verified/rejected |
| verified_at | DATETIME(3) | 是 | — | 认证通过时间 |
| reviewer_id | BIGINT UNSIGNED | 是 | — | 人工审核员 |
| reject_reason | VARCHAR(255) | 是 | — | 驳回原因 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

> `UNIQUE(campus_id, student_id_search)` 允许不同校园同号；`FOREIGN KEY (campus_id, user_id) REFERENCES users(campus_id, id)` 保证用户同校。

### 2.9 user_profiles — 用户画像
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK，唯一） |
| college | VARCHAR(128) | 是 | — | 学院 |
| grade | VARCHAR(32) | 是 | — | 年级 |
| skills | JSON | 是 | — | 技能标签 |
| interests | JSON | 是 | — | 兴趣标签 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 2.10 roles — 角色
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| code | VARCHAR(64) | 否 | — | 角色编码（唯一） |
| name | VARCHAR(64) | 否 | — | 角色名称 |
| description | VARCHAR(255) | 是 | — | 描述 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 2.11 user_roles — 用户角色
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| role_id | BIGINT UNSIGNED | 否 | — | 角色（FK） |
| campus_id | BIGINT UNSIGNED | 否 | 0 | 0 表示平台级角色 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

> `campus_id` 非空（0 为平台级），`UNIQUE(user_id, role_id, campus_id)` 保证平台角色不重复分配。

### 2.12 authorizations — 授权记录
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| type | VARCHAR(32) | 否 | — | phone/privacy/subscription/location |
| scope | VARCHAR(128) | 是 | — | 授权范围 |
| status | VARCHAR(32) | 否 | granted | granted/revoked |
| version | INT UNSIGNED | 否 | 0 | 授权版本（可追溯） |
| granted_at | DATETIME(3) | 否 | — | 授权时间 |
| revoked_at | DATETIME(3) | 是 | — | 撤销时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

---

## 三、机会与材料（V2，17 表）

### 3.1 notices — 通知原文
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| title | VARCHAR(256) | 否 | — | 标题 |
| content | MEDIUMTEXT | 是 | — | 原文内容 |
| raw_url | VARCHAR(1024) | 是 | — | 原始链接 |
| publisher | VARCHAR(128) | 是 | — | 发布主体 |
| published_at | DATETIME(3) | 是 | — | 发布时间 |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| source_name | VARCHAR(128) | 是 | — | 来源名称 |
| external_id | VARCHAR(128) | 是 | — | 外部编号 |
| official_reference | VARCHAR(512) | 是 | — | 官方引用 |
| is_stale | TINYINT(1) | 否 | 0 | 是否过期 |
| status | VARCHAR(32) | 否 | pending | pending/parsing/reviewing/published/archived |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_by / updated_by | BIGINT UNSIGNED | 是 | — | 操作人 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 3.2 ai_parse_tasks — AI 解析任务
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| notice_id | BIGINT UNSIGNED | 否 | — | 通知（FK） |
| model | VARCHAR(64) | 是 | — | 模型标识 |
| status | VARCHAR(32) | 否 | pending | pending/processing/succeeded/failed |
| result_json | JSON | 是 | — | 解析结果 |
| confidence | DECIMAL(5,4) | 是 | — | 置信度 |
| error | VARCHAR(1024) | 是 | — | 错误信息 |
| input_digest | CHAR(64) | 是 | — | 输入摘要 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 3.3 ai_parse_fields — AI 解析字段
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| parse_task_id | BIGINT UNSIGNED | 否 | — | 解析任务（FK） |
| field_key | VARCHAR(64) | 否 | — | 字段键 |
| field_value | TEXT | 是 | — | 字段值 |
| evidence_location | VARCHAR(512) | 是 | — | 原文定位 |
| confidence | DECIMAL(5,4) | 是 | — | 置信度 |
| human_confirmed | TINYINT(1) | 否 | 0 | 是否人工确认 |
| human_value | TEXT | 是 | — | 人工修正值 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.4 opportunities — 机会
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| notice_id | BIGINT UNSIGNED | 是 | — | 关联通知（FK） |
| title | VARCHAR(256) | 否 | — | 标题 |
| category | VARCHAR(32) | 否 | — | scholarship/competition/research/internship/volunteer |
| organizer | VARCHAR(128) | 是 | — | 主办方 |
| summary | TEXT | 是 | — | 摘要 |
| deadline | DATETIME(3) | 是 | — | 截止时间 |
| status | VARCHAR(32) | 否 | published | draft/parsing/reviewing/published/expired/archived |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_by / updated_by | BIGINT UNSIGNED | 是 | — | 操作人 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 3.5 opportunity_eligibility_rules — 资格规则
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| opportunity_id | BIGINT UNSIGNED | 否 | — | 机会（FK） |
| field_key | VARCHAR(64) | 否 | — | 匹配字段（college/grade/skill） |
| operator | VARCHAR(16) | 否 | — | eq/ne/in/contains/range |
| value_json | JSON | 否 | — | 规则值 |
| severity | VARCHAR(16) | 否 | hard | hard/soft |
| sort_order | INT UNSIGNED | 否 | 0 | 排序 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.6 opportunity_eligibility_checks — 资格匹配结果
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| opportunity_id | BIGINT UNSIGNED | 否 | — | 机会（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| conclusion | VARCHAR(16) | 否 | — | eligible/possible/not_eligible |
| reason_json | JSON | 是 | — | 冲突条件说明 |
| rules_version | INT UNSIGNED | 是 | — | 规则版本 |
| profile_snapshot | JSON | 是 | — | 个人画像快照 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.7 opportunity_material_items — 机会材料清单项
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| opportunity_id | BIGINT UNSIGNED | 否 | — | 机会（FK） |
| name | VARCHAR(128) | 否 | — | 材料名称 |
| description | VARCHAR(512) | 是 | — | 说明 |
| required | TINYINT(1) | 否 | 1 | 是否必需 |
| sort_order | INT UNSIGNED | 否 | 0 | 排序 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.8 favorites — 收藏
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| target_type | VARCHAR(32) | 否 | — | opportunity/resource/mutual_aid_post/community_post |
| target_id | BIGINT UNSIGNED | 否 | — | 目标 ID |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.9 reminders — 提醒
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| target_type | VARCHAR(32) | 否 | — | opportunity/material/borrow/reservation |
| target_id | BIGINT UNSIGNED | 否 | — | 目标 ID |
| remind_at | DATETIME(3) | 否 | — | 提醒时间 |
| status | VARCHAR(32) | 否 | pending | pending/sent/cancelled |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.10 user_opportunity_submissions — 用户提交记录
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| opportunity_id | BIGINT UNSIGNED | 否 | — | 机会（FK） |
| status | VARCHAR(32) | 否 | submitted | submitted |
| submitted_at | DATETIME(3) | 否 | — | 标记提交时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.11 material_files — 材料文件元数据
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 所有者（FK） |
| object_key | VARCHAR(512) | 否 | — | MinIO 对象键 |
| file_hash | CHAR(64) | 否 | — | 内容散列（去重） |
| size_bytes | BIGINT UNSIGNED | 否 | — | 大小 |
| mime_type | VARCHAR(128) | 否 | — | 类型 |
| file_name | VARCHAR(255) | 是 | — | 原文件名 |
| expires_at | DATETIME(3) | 是 | — | 材料有效期 |
| tags_json | JSON | 是 | — | 标签 |
| encrypted | TINYINT(1) | 否 | 0 | 是否加密 |
| scan_status | VARCHAR(32) | 否 | pending | pending/clean/infected |
| status | VARCHAR(32) | 否 | active | active/deleted |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 3.12 material_versions — 材料文件版本
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| file_id | BIGINT UNSIGNED | 否 | — | 文件（FK） |
| version_no | INT UNSIGNED | 否 | — | 版本号 |
| object_key | VARCHAR(512) | 否 | — | 该版本对象键 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

> 版本不可变：业务层只允许 INSERT，不得 UPDATE 已有版本。

### 3.13 material_checklists — 材料清单
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| opportunity_id | BIGINT UNSIGNED | 否 | — | 机会（FK） |
| status | VARCHAR(32) | 否 | draft | draft/ready |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 3.14 material_checklist_items — 材料清单项
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| checklist_id | BIGINT UNSIGNED | 否 | — | 清单（FK） |
| item_name | VARCHAR(128) | 否 | — | 项名 |
| required | TINYINT(1) | 否 | 1 | 是否必需 |
| material_version_id | BIGINT UNSIGNED | 是 | — | 绑定的文件版本（不可变，FK） |
| status | VARCHAR(32) | 否 | missing | missing/uploaded/prechecking/needs_fix/basic_complete |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 3.15 precheck_tasks — 预检任务
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| checklist_id | BIGINT UNSIGNED | 否 | — | 清单（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| status | VARCHAR(32) | 否 | pending | pending/running/succeeded/failed |
| result_json | JSON | 是 | — | 结果汇总 |
| checklist_version | INT UNSIGNED | 否 | — | 清单版本 |
| rules_version | INT UNSIGNED | 否 | — | 规则版本 |
| input_digest | CHAR(64) | 否 | — | 输入摘要 |
| input_snapshot | JSON | 否 | — | 不可变输入快照（清单项+文件版本+规则） |
| model_config_version | VARCHAR(64) | 是 | — | 模型配置版本 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 3.16 precheck_results — 预检结果
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| precheck_task_id | BIGINT UNSIGNED | 否 | — | 预检任务（FK） |
| checklist_item_id | BIGINT UNSIGNED | 否 | — | 关联清单项（FK） |
| item_key | VARCHAR(64) | 否 | — | 检查项键（兼容） |
| level | VARCHAR(16) | 否 | — | passed/confirm/missing |
| message | VARCHAR(512) | 是 | — | 提示 |
| evidence_json | JSON | 是 | — | 证据 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 3.17 material_usage_records — 材料使用记录
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| file_id | BIGINT UNSIGNED | 否 | — | 文件（FK） |
| used_in_type | VARCHAR(32) | 否 | — | 使用场景 |
| used_in_id | BIGINT UNSIGNED | 否 | — | 场景 ID |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

---

## 四、服务与预约（V3，13 表）

### 4.1 work_orders — 报修工单
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 报修人（复合 FK 保证同校） |
| assignee_id | BIGINT UNSIGNED | 是 | — | 当前处理人 |
| category | VARCHAR(32) | 否 | — | dorm/facility/other |
| location | VARCHAR(255) | 是 | — | 位置 |
| description | VARCHAR(1024) | 否 | — | 描述 |
| urgency | VARCHAR(16) | 否 | normal | normal/urgent |
| status | VARCHAR(32) | 否 | pending | pending/reviewing/accepted/processing/rated/completed/rejected |
| images_json | JSON | 是 | — | 图片对象键列表 |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_by / updated_by | BIGINT UNSIGNED | 是 | — | 操作人 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 4.2 work_order_events — 工单状态流转
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| work_order_id | BIGINT UNSIGNED | 否 | — | 工单（FK） |
| from_status | VARCHAR(32) | 是 | — | 原状态 |
| to_status | VARCHAR(32) | 否 | — | 新状态 |
| operator_id | BIGINT UNSIGNED | 是 | — | 操作人 |
| comment | VARCHAR(512) | 是 | — | 备注 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.3 work_order_ratings — 工单评价
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| work_order_id | BIGINT UNSIGNED | 否 | — | 工单（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 评价人（FK） |
| score | TINYINT UNSIGNED | 否 | — | 1~5（CHECK） |
| comment | VARCHAR(512) | 是 | — | 评价 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.4 campus_issues — 校园问题
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 发布人（复合 FK 保证同校） |
| title | VARCHAR(256) | 否 | — | 标题 |
| category | VARCHAR(32) | 否 | — | repair/canteen/express/other |
| description | VARCHAR(1024) | 是 | — | 描述 |
| status | VARCHAR(32) | 否 | pending | pending/reviewing/published/hidden/processing/completed |
| follow_count | INT UNSIGNED | 否 | 0 | 关注数 |
| vote_count | INT UNSIGNED | 否 | 0 | 投票数 |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 4.5 campus_issue_locations — 问题地图位置
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| issue_id | BIGINT UNSIGNED | 否 | — | 问题（FK） |
| name | VARCHAR(128) | 是 | — | 位置名 |
| latitude | DECIMAL(10,7) | 否 | — | 纬度 |
| longitude | DECIMAL(10,7) | 否 | — | 经度 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.6 campus_issue_follows — 问题关注
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| issue_id | BIGINT UNSIGNED | 否 | — | 问题（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.7 campus_issue_evidences — 问题补充证据
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| issue_id | BIGINT UNSIGNED | 否 | — | 问题（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| content | VARCHAR(512) | 是 | — | 内容 |
| images_json | JSON | 是 | — | 图片列表 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.8 campus_issue_votes — 问题投票
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| issue_id | BIGINT UNSIGNED | 否 | — | 问题（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| value | TINYINT | 否 | 1 | 1 赞同 / -1 反对（CHECK） |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 4.9 resources — 资源
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| region_id | BIGINT UNSIGNED | 是 | — | 所在区域（FK） |
| type | VARCHAR(32) | 否 | — | library_seat/classroom/equipment |
| name | VARCHAR(128) | 否 | — | 名称 |
| capacity | INT UNSIGNED | 是 | — | 容量 |
| equipment_json | JSON | 是 | — | 设备列表 |
| status | VARCHAR(32) | 否 | active | 状态 |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 4.10 resource_slots — 资源时段
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| resource_id | BIGINT UNSIGNED | 否 | — | 资源（FK） |
| slot_date | DATE | 否 | — | 日期 |
| start_time | TIME | 否 | — | 开始 |
| end_time | TIME | 否 | — | 结束（CHECK 晚于开始） |
| capacity | INT UNSIGNED | 是 | — | 容量 |
| status | VARCHAR(32) | 否 | available | available/maintenance |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 4.11 reservations — 预约（历史记录）
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（复合 FK 保证同校） |
| slot_id | BIGINT UNSIGNED | 否 | — | 时段（FK，资源由此派生） |
| seat_no | INT UNSIGNED | 否 | 0 | 0 独占，1..N 容量座位 |
| status | VARCHAR(32) | 否 | pending | pending/confirmed/checked_in/completed/cancelled/no_show/rejected |
| source_type | VARCHAR(16) | 否 | demo | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

> `UNIQUE(id, slot_id, seat_no)` 供占用表的复合外键引用，保证占用与预约的时段、座位一致。

### 4.12 resource_occupancies — 资源有效占用
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| slot_id | BIGINT UNSIGNED | 否 | — | 时段（FK，资源由此派生） |
| reservation_id | BIGINT UNSIGNED | 否 | — | 预约（FK） |
| seat_no | INT UNSIGNED | 否 | 0 | 0 独占，1..N 容量座位 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

> 只保存有效行；`UNIQUE(slot_id, seat_no)` 防超卖；`UNIQUE(reservation_id)` 一条预约一条占用；复合外键 `(reservation_id, slot_id, seat_no) → reservations(id, slot_id, seat_no)` 保证时段座位与预约一致；取消/拒绝时删除本行释放。

### 4.13 reservation_checkins — 预约签到
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| reservation_id | BIGINT UNSIGNED | 否 | — | 预约（FK，唯一） |
| checked_in_at | DATETIME(3) | 否 | — | 签到时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

---

## 五、互助与社区（V4，12 表）

### 5.1 mutual_aid_posts — 互助发布
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 发布人（复合 FK 保证同校） |
| category | VARCHAR(32) | 否 | — | lost_found/secondhand/textbook/skill/borrow |
| title | VARCHAR(256) | 否 | — | 标题 |
| content | VARCHAR(2048) | 是 | — | 内容 |
| detail_json | JSON | 是 | — | 分类结构化详情（失物类型/发生时间地点等） |
| images_json | JSON | 是 | — | 图片 |
| price | DECIMAL(10,2) | 是 | — | 价格（CHECK 非负） |
| exchange_area | VARCHAR(128) | 是 | — | 交接区域 |
| status | VARCHAR(32) | 否 | pending | pending/reviewing/published/communicating/reserved/completed/cancelled/rejected |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_by / updated_by | BIGINT UNSIGNED | 是 | — | 操作人 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 5.2 lost_found_matches — 失物匹配
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| lost_post_id | BIGINT UNSIGNED | 否 | — | 丢失帖（FK） |
| found_post_id | BIGINT UNSIGNED | 否 | — | 拾得帖（FK） |
| similarity | DECIMAL(5,4) | 是 | — | 相似度 |
| status | VARCHAR(32) | 否 | suggested | suggested/verified/returned/ignored |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 5.3 borrow_requests — 借用申请
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| post_id | BIGINT UNSIGNED | 否 | — | 发布帖（FK） |
| borrower_id | BIGINT UNSIGNED | 否 | — | 借用人（FK） |
| start_time | DATETIME(3) | 是 | — | 开始 |
| end_time | DATETIME(3) | 是 | — | 结束（CHECK 晚于开始） |
| status | VARCHAR(32) | 否 | pending | pending/confirmed/handed_over/returned/cancelled |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 5.4 teams — 组队
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| opportunity_id | BIGINT UNSIGNED | 是 | — | 关联竞赛机会（FK） |
| leader_id | BIGINT UNSIGNED | 否 | — | 队长（FK） |
| title | VARCHAR(256) | 否 | — | 标题 |
| goal | VARCHAR(1024) | 是 | — | 目标 |
| status | VARCHAR(32) | 否 | recruiting | recruiting/full/closed |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 5.5 team_members — 团队成员
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| team_id | BIGINT UNSIGNED | 否 | — | 团队（FK） |
| user_id | BIGINT UNSIGNED | 是 | — | 成员（空=待补缺口，FK） |
| role | VARCHAR(64) | 是 | — | 角色 |
| skill_tags | JSON | 是 | — | 技能标签 |
| status | VARCHAR(32) | 否 | member | member/open(缺口) |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

> `UNIQUE(team_id, user_id)` 禁止同一用户重复成为同一团队成员；`user_id` 为空的角色缺口可多条。

### 5.6 team_applications — 组队申请
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| team_id | BIGINT UNSIGNED | 否 | — | 团队（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 申请人（FK） |
| message | VARCHAR(512) | 是 | — | 申请说明 |
| status | VARCHAR(32) | 否 | pending | pending/approved/rejected |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 5.7 community_posts — 匿名社区帖子
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| pseudo_identity_id | BIGINT UNSIGNED | 否 | — | 每帖独立随机身份 |
| author_name | VARCHAR(64) | 否 | — | 随机匿名昵称（展示用，每帖独立） |
| title | VARCHAR(256) | 否 | — | 标题 |
| content | TEXT | 是 | — | 内容 |
| status | VARCHAR(32) | 否 | pending | pending/published/reported/restricted/deleted |
| is_high_risk | TINYINT(1) | 否 | 0 | 高风险转专门队列 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 5.8 community_comments — 匿名社区评论
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| post_id | BIGINT UNSIGNED | 否 | — | 帖子（FK） |
| pseudo_identity_id | BIGINT UNSIGNED | 否 | — | 匿名身份 |
| author_name | VARCHAR(64) | 否 | — | 随机匿名昵称（展示用） |
| content | VARCHAR(2048) | 否 | — | 内容 |
| status | VARCHAR(32) | 否 | pending | pending/published/deleted |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 5.9 pseudo_identities — 匿名身份映射（受限）
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| post_id | BIGINT UNSIGNED | 否 | — | 帖子（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 真实用户（FK，仅调查账号可读） |
| random_name | VARCHAR(64) | 否 | — | 每帖独立随机身份 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 5.10 reports — 举报
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| reporter_id | BIGINT UNSIGNED | 否 | — | 举报人（复合 FK 保证同校） |
| target_type | VARCHAR(32) | 否 | — | community_post/community_comment/mutual_aid_post/message |
| target_id | BIGINT UNSIGNED | 否 | — | 目标 ID |
| reason | VARCHAR(512) | 否 | — | 理由 |
| status | VARCHAR(32) | 否 | pending | pending/reviewing/resolved/dismissed |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 5.11 penalties — 处罚
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| penalty_type | VARCHAR(32) | 否 | — | remind/rate_limit/take_down/mute/ban |
| reason | VARCHAR(512) | 否 | — | 原因 |
| expires_at | DATETIME(3) | 是 | — | 到期时间 |
| status | VARCHAR(32) | 否 | active | active/lifted |
| created_by | BIGINT UNSIGNED | 否 | — | 操作人 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 5.12 appeals — 申诉
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| penalty_id | BIGINT UNSIGNED | 是 | — | 关联处罚（FK） |
| reason | VARCHAR(512) | 否 | — | 申诉理由 |
| status | VARCHAR(32) | 否 | pending | pending/approved/rejected |
| reviewed_by | BIGINT UNSIGNED | 是 | — | 复核人 |
| reviewed_at | DATETIME(3) | 是 | — | 复核时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

---

## 六、消息、治理与集成（V5，16 表）

### 6.1 conversations — 会话
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| type | VARCHAR(32) | 否 | direct | direct/group/team |
| title | VARCHAR(128) | 是 | — | 会话标题 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 6.2 conversation_members — 会话成员
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| conversation_id | BIGINT UNSIGNED | 否 | — | 会话（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| unread_count | INT UNSIGNED | 否 | 0 | 未读数 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.3 messages — 消息
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| conversation_id | BIGINT UNSIGNED | 否 | — | 会话（FK） |
| sender_id | BIGINT UNSIGNED | 否 | — | 发送人（FK） |
| content | TEXT | 否 | — | 内容 |
| status | VARCHAR(32) | 否 | sent | sent/reported/blocked |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.4 notifications — 站内通知
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（复合 FK 保证同校） |
| type | VARCHAR(32) | 否 | — | deadline/workorder/reservation/borrow/system |
| title | VARCHAR(128) | 否 | — | 标题 |
| content | VARCHAR(512) | 否 | — | 内容 |
| target_type | VARCHAR(32) | 是 | — | 跳转类型 |
| target_id | BIGINT UNSIGNED | 是 | — | 跳转 ID |
| read_at | DATETIME(3) | 是 | — | 已读时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.5 subscription_authorizations — 订阅授权
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| template_id | VARCHAR(128) | 否 | — | 订阅消息模板 |
| scene | VARCHAR(64) | 否 | — | 授权场景 |
| status | VARCHAR(32) | 否 | granted | granted/revoked |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| granted_at | DATETIME(3) | 否 | — | 授权时间 |
| revoked_at | DATETIME(3) | 是 | — | 撤销时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.6 message_send_records — 消息发送记录
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| channel | VARCHAR(32) | 否 | — | in_app/wechat_subscribe |
| template_id | VARCHAR(128) | 是 | — | 模板 |
| content_json | JSON | 是 | — | 内容 |
| dedup_key | CHAR(64) | 是 | — | 业务去重键（唯一） |
| result | VARCHAR(32) | 否 | pending | pending/success/failed |
| error | VARCHAR(512) | 是 | — | 错误 |
| retry_count | INT UNSIGNED | 否 | 0 | 重试次数 |
| next_retry_at | DATETIME(3) | 是 | — | 下次重试时间 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.7 moderation_tasks — 审核任务
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| target_type | VARCHAR(32) | 否 | — | 审核对象类型 |
| target_id | BIGINT UNSIGNED | 否 | — | 审核对象 ID |
| status | VARCHAR(32) | 否 | pending | pending/assigned/resolved |
| assigned_to | BIGINT UNSIGNED | 是 | — | 审核员 |
| result | VARCHAR(32) | 是 | — | approve/reject/restrict |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 6.8 moderation_rules — 审核规则
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| rule_type | VARCHAR(32) | 否 | — | keyword/regex/model |
| pattern | VARCHAR(1024) | 否 | — | 规则内容 |
| action | VARCHAR(32) | 否 | — | block/review/restrict |
| enabled | TINYINT(1) | 否 | 1 | 是否启用 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.9 credit_events — 信用事件
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（复合 FK 保证同校） |
| event_type | VARCHAR(32) | 否 | — | no_show/late_cancel/damage |
| reason | VARCHAR(512) | 否 | — | 原因 |
| points | INT | 否 | 0 | 信用分变动 |
| related_type | VARCHAR(32) | 是 | — | 关联类型 |
| related_id | BIGINT UNSIGNED | 是 | — | 关联 ID |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.10 admin_audit_logs — 管理员操作审计
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| operator_id | BIGINT UNSIGNED | 否 | — | 操作人（FK） |
| action | VARCHAR(64) | 否 | — | 操作 |
| target_type | VARCHAR(32) | 是 | — | 对象类型 |
| target_id | BIGINT UNSIGNED | 是 | — | 对象 ID |
| reason | VARCHAR(512) | 是 | — | 原因 |
| detail_json | JSON | 是 | — | 详情 |
| created_at | DATETIME(3) | 否 | — | 创建时间（只追加，不可删改） |

### 6.11 security_investigations — 安全调查记录
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| investigator_id | BIGINT UNSIGNED | 否 | — | 调查员（FK） |
| reason | VARCHAR(512) | 否 | — | 调查原因 |
| target_user_id | BIGINT UNSIGNED | 是 | — | 目标用户 |
| target_type | VARCHAR(32) | 是 | — | 对象类型 |
| target_id | BIGINT UNSIGNED | 是 | — | 对象 ID |
| result_json | JSON | 是 | — | 结果 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.12 external_systems — 外部系统
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| type | VARCHAR(32) | 否 | — | notice/library/classroom/repair/crowd |
| provider | VARCHAR(32) | 否 | demo | demo/manual/official |
| config_json | JSON | 是 | — | 接口配置（不含密钥明文） |
| status | VARCHAR(32) | 否 | active | 状态 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 6.13 external_references — 外部编号映射
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| external_system_id | BIGINT UNSIGNED | 否 | — | 外部系统（FK） |
| local_type | VARCHAR(32) | 否 | — | 本地类型 |
| local_id | BIGINT UNSIGNED | 否 | — | 本地 ID |
| external_id | VARCHAR(128) | 否 | — | 外部编号 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

> `UNIQUE(external_system_id, external_id)`：一个外部对象只映射一个本地记录。

### 6.14 sync_cursors — 同步游标
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| external_system_id | BIGINT UNSIGNED | 否 | — | 外部系统（FK） |
| cursor_value | VARCHAR(256) | 是 | — | 增量游标 |
| last_synced_at | DATETIME(3) | 是 | — | 上次同步 |
| updated_at | DATETIME(3) | 否 | — | 更新时间 |

### 6.15 sync_raw_digests — 同步原始摘要
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| external_system_id | BIGINT UNSIGNED | 否 | — | 外部系统（FK） |
| external_id | VARCHAR(128) | 否 | — | 外部编号 |
| digest | CHAR(64) | 否 | — | 内容摘要 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 6.16 sync_errors — 同步错误
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| external_system_id | BIGINT UNSIGNED | 否 | — | 外部系统（FK） |
| external_id | VARCHAR(128) | 是 | — | 外部编号 |
| error | VARCHAR(1024) | 否 | — | 错误信息 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

---

## 七、补充模型（V6，4 表）

### 7.1 crowd_observations — 拥堵观测
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| campus_id | BIGINT UNSIGNED | 否 | — | 所属校园（FK） |
| location_type | VARCHAR(32) | 否 | — | canteen/express |
| location_name | VARCHAR(128) | 否 | — | 位置名 |
| crowd_level | VARCHAR(16) | 否 | — | low/medium/high |
| source_type | VARCHAR(16) | 否 | manual | demo/manual/official |
| observed_at | DATETIME(3) | 否 | — | 观测时间 |
| is_stale | TINYINT(1) | 否 | 0 | 是否过期 |
| created_at | DATETIME(3) | 否 | — | 创建时间 |

### 7.2 growth_records — 成长档案
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| type | VARCHAR(32) | 否 | — | competition/research/volunteer/mutual_aid |
| title | VARCHAR(256) | 否 | — | 标题 |
| description | VARCHAR(1024) | 是 | — | 描述 |
| proof_file_id | BIGINT UNSIGNED | 是 | — | 证明文件（FK） |
| confirmed | TINYINT(1) | 否 | 0 | 用户主动确认 |
| occurred_at | DATETIME(3) | 是 | — | 发生时间 |
| version | INT UNSIGNED | 否 | 0 | 乐观锁 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
| deleted_at | DATETIME(3) | 是 | — | 软删除 |

### 7.3 data_requests — 数据权利申请
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| user_id | BIGINT UNSIGNED | 否 | — | 用户（FK） |
| type | VARCHAR(32) | 否 | — | export/deletion |
| status | VARCHAR(32) | 否 | pending | pending/processing/completed/rejected |
| requested_at | DATETIME(3) | 否 | — | 申请时间 |
| processed_at | DATETIME(3) | 是 | — | 处理时间 |
| retention_basis | VARCHAR(255) | 是 | — | 保留依据 |
| result_json | JSON | 是 | — | 处理结果 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |

### 7.4 async_jobs — 异步任务
| 字段 | 类型 | 空 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT UNSIGNED | 否 | — | 全局唯一 ID |
| job_type | VARCHAR(64) | 否 | — | 任务类型 |
| payload_json | JSON | 否 | — | 载荷 |
| status | VARCHAR(32) | 否 | pending | pending/running/succeeded/failed |
| dedup_key | CHAR(64) | 是 | — | 业务去重键 |
| retry_count | INT UNSIGNED | 否 | 0 | 重试次数 |
| max_retries | INT UNSIGNED | 否 | 3 | 最大重试 |
| next_retry_at | DATETIME(3) | 是 | — | 下次重试 |
| picked_at | DATETIME(3) | 是 | — | 领取时间 |
| result_json | JSON | 是 | — | 结果 |
| error | VARCHAR(1024) | 是 | — | 错误 |
| created_at / updated_at | DATETIME(3) | 否 | — | 时间 |
