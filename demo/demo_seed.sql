-- =============================================================
-- 材智通 · 演示种子数据（仅演示环境执行，生产不使用）
-- 说明：
--   · source_type 一律 demo
--   · 固定 ID 便于引用；生产由应用层生成雪花 ID
--   · 角色已在 V1 初始化，本文件只灌入静态演示数据
--   · 用户相关动态数据（申请/预约/工单）由正式 API 运行时产生
-- =============================================================

SET NAMES utf8mb4;

-- 演示校园租户
INSERT INTO campuses (id, name, code, status, version, created_at, updated_at) VALUES
  (1, '示范大学', 'demo-univ', 'active', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 演示账号（仅承载管理后台维护的演示帖子/问题，无真实 openid）
INSERT INTO users (id, campus_id, nickname, avatar_url, status, version, created_at, updated_at) VALUES
  (1, 1, '示范学生', NULL, 'active', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 通知原文（演示数据）
INSERT INTO notices (id, campus_id, title, content, publisher, published_at, source_type, source_name, is_stale, status, version, created_at, updated_at) VALUES
  (1, 1, '关于 2026 年示范大学综合奖学金申请的通知',
   '根据学校工作安排，现开展本学年综合奖学金申请。申请条件：在籍学生、无处分记录、学业成绩符合要求。材料：成绩单、获奖证明。',
   '示范大学学生工作处', '2026-09-20 10:00:00', 'demo', '示范大学', 0, 'published', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, '示范大学程序设计竞赛报名通知',
   '面向全校学生的程序设计竞赛现已开放报名，团队 1~5 人，需确定指导教师，提交报名表与参赛承诺书。',
   '示范大学教务处', '2026-10-01 09:00:00', 'demo', '示范大学', 0, 'published', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (3, 1, '示范大学实验室科研助手招募',
   '某重点实验室招募本科生科研助手，方向为机器学习与数据分析，要求具备编程基础。',
   '示范大学科研处', '2026-10-05 14:00:00', 'demo', '示范大学', 0, 'published', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 机会（演示数据）
INSERT INTO opportunities (id, campus_id, notice_id, title, category, organizer, summary, deadline, status, source_type, version, created_at, updated_at) VALUES
  (1, 1, 1, '综合奖学金申请', 'scholarship', '示范大学学生工作处', '按规则核对奖学金申请信息与材料完整性。', '2026-11-30 23:59:59', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, 2, '程序设计竞赛报名', 'competition', '示范大学教务处', '团队 1~5 人组队参赛，需确定指导教师。', '2026-11-15 23:59:59', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (3, 1, 3, '科研助手招募', 'research', '示范大学科研处', '机器学习方向本科生科研助手，具备编程基础。', '2026-12-01 23:59:59', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (4, 1, NULL, '校园志愿服务招募', 'volunteer', '示范大学团委', '校庆活动志愿者招募，累计志愿服务时长。', '2026-12-10 23:59:59', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 资格规则（演示数据）
INSERT INTO opportunity_eligibility_rules (id, opportunity_id, field_key, operator, value_json, severity, sort_order, created_at) VALUES
  (1, 1, 'college', 'eq', JSON_OBJECT('value', '全部学院'), 'soft', 1, UTC_TIMESTAMP(3)),
  (2, 2, 'grade', 'in', JSON_OBJECT('values', JSON_ARRAY('大一', '大二', '大三', '大四')), 'hard', 1, UTC_TIMESTAMP(3)),
  (3, 3, 'skills', 'contains', JSON_OBJECT('value', '编程'), 'hard', 1, UTC_TIMESTAMP(3)),
  (4, 4, 'college', 'eq', JSON_OBJECT('value', '全部学院'), 'soft', 1, UTC_TIMESTAMP(3));

-- 机会材料清单项（演示数据）
INSERT INTO opportunity_material_items (id, opportunity_id, name, description, required, sort_order, created_at) VALUES
  (1, 1, '成绩单', '本学期成绩单，盖章有效', 1, 1, UTC_TIMESTAMP(3)),
  (2, 1, '获奖证明', '获奖证书扫描件，选填', 0, 2, UTC_TIMESTAMP(3)),
  (3, 2, '报名表', '团队报名表，含成员信息', 1, 1, UTC_TIMESTAMP(3)),
  (4, 2, '参赛承诺书', '参赛承诺书，选填', 0, 2, UTC_TIMESTAMP(3)),
  (5, 3, '个人简历', '个人简历，含项目经历', 1, 1, UTC_TIMESTAMP(3)),
  (6, 4, '志愿者报名表', '志愿者报名表', 1, 1, UTC_TIMESTAMP(3));

-- 资源（演示数据）
INSERT INTO resources (id, campus_id, region_id, type, name, capacity, equipment_json, status, source_type, version, created_at, updated_at) VALUES
  (1, 1, NULL, 'library_seat', '图书馆三楼自习区座位', 120, NULL, 'active', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, NULL, 'classroom', '教一 203 自习教室', 60, JSON_OBJECT('projector', true), 'active', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (3, 1, NULL, 'classroom', '研讨室 A', 10, JSON_OBJECT('whiteboard', true), 'active', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 资源时段（演示数据：明天几个时段）
INSERT INTO resource_slots (id, resource_id, slot_date, start_time, end_time, capacity, status, version, created_at, updated_at) VALUES
  (1, 1, CURDATE() + INTERVAL 1 DAY, '08:00:00', '10:00:00', 40, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, CURDATE() + INTERVAL 1 DAY, '10:00:00', '12:00:00', 40, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (3, 2, CURDATE() + INTERVAL 1 DAY, '14:00:00', '16:00:00', 60, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (4, 2, CURDATE() + INTERVAL 1 DAY, '16:00:00', '18:00:00', 60, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (5, 3, CURDATE() + INTERVAL 1 DAY, '09:00:00', '11:00:00', 10, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (6, 3, CURDATE() + INTERVAL 1 DAY, '19:00:00', '21:00:00', 10, 'available', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 互助发布（演示数据）
INSERT INTO mutual_aid_posts (id, campus_id, user_id, category, title, content, detail_json, price, exchange_area, status, source_type, version, created_at, updated_at) VALUES
  (1, 1, 1, 'lost_found', '拾到黑色钱包一个', '在图书馆三楼拾到黑色钱包，内含校园卡，请失主联系核对。',
   JSON_OBJECT('kind', 'found', 'occurred_at', '2026-10-12 16:00:00', 'place', '图书馆三楼'),
   NULL, '图书馆三楼', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, 1, 'secondhand', '转卖《数据结构》教材', '九成新，仅少量笔记，当面交易。', NULL, 25.00, '东区食堂门口', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (3, 1, 1, 'borrow', '借用羽毛球拍', '周六借用一副羽毛球拍，周日归还，可押学生证。', NULL, NULL, '体育馆', 'published', 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 校园问题（演示数据）
INSERT INTO campus_issues (id, campus_id, user_id, title, category, description, status, follow_count, vote_count, source_type, version, created_at, updated_at) VALUES
  (1, 1, 1, '东区三舍热水供应不稳定', 'repair', '近期晚间热水时断时续，希望尽快检修。', 'processing', 12, 8, 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  (2, 1, 1, '食堂二楼午餐时段排队较长', 'canteen', '12:00 前后排队时间较长，建议错峰或增开窗口。', 'published', 20, 15, 'demo', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 拥堵观测（演示数据）
INSERT INTO crowd_observations (id, campus_id, location_type, location_name, crowd_level, source_type, observed_at, is_stale, created_at) VALUES
  (1, 1, 'canteen', '食堂二楼', 'high', 'demo', '2026-10-13 12:10:00', 0, UTC_TIMESTAMP(3)),
  (2, 1, 'express', '东区快递站', 'medium', 'demo', '2026-10-13 12:10:00', 0, UTC_TIMESTAMP(3));
