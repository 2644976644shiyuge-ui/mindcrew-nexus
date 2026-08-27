-- ═══════════════════════════════════════════════════════════════════
-- 全球获客数字员工 (Global Lead Hunter) · 表结构
--   lead_hunt_session  每次猎单任务（配置 / 进度 / ICP / 统计）
--   lead_hunt_company  发现的公司（26 字段中的公司侧字段）
--   lead_hunt_contact  联系人（人名 / 头衔 / 邮箱 / 验证状态）
-- ═══════════════════════════════════════════════════════════════════
USE docmind;

-- 1) 任务会话表
CREATE TABLE IF NOT EXISTS lead_hunt_session (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT       NOT NULL COMMENT '发起用户',
  countries     VARCHAR(500) NOT NULL COMMENT '目标国家（逗号分隔，如 United States,Canada）',
  customer_types VARCHAR(300)          DEFAULT NULL COMMENT '客户类型（逗号分隔：Distributor,System Integrator,...）',
  products      VARCHAR(500)          DEFAULT NULL COMMENT '关注产品线（逗号分隔）',
  target_count  INT                    DEFAULT 50 COMMENT '目标线索数',
  status        VARCHAR(20)           DEFAULT 'queued' COMMENT 'queued / running / done / failed',
  current_step  INT                    DEFAULT 0 COMMENT '当前步骤 1-11',
  progress      INT                    DEFAULT 0 COMMENT '总进度 0-100',
  icp_summary   TEXT                  DEFAULT NULL COMMENT 'LLM 生成的 ICP 摘要（Markdown）',
  step_logs     TEXT                  DEFAULT NULL COMMENT '11 步执行日志（JSON）',
  stats_json    VARCHAR(1000)         DEFAULT NULL COMMENT '统计：发现/去重/拒绝/最终',
  error_msg     VARCHAR(1000)         DEFAULT NULL,
  create_time   DATETIME              DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT               DEFAULT 0,
  KEY idx_lh_user (user_id),
  KEY idx_lh_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '全球获客-任务会话';

-- 2) 公司表
CREATE TABLE IF NOT EXISTS lead_hunt_company (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id    BIGINT       NOT NULL,
  name          VARCHAR(200) DEFAULT NULL COMMENT 'Company',
  website       VARCHAR(300) DEFAULT NULL COMMENT 'Website',
  domain        VARCHAR(200) DEFAULT NULL COMMENT '主域名（去重键）',
  country       VARCHAR(100) DEFAULT NULL COMMENT 'Country',
  region        VARCHAR(100) DEFAULT NULL COMMENT 'Region',
  city          VARCHAR(100) DEFAULT NULL COMMENT 'City',
  state         VARCHAR(100) DEFAULT NULL COMMENT 'State',
  address       VARCHAR(300) DEFAULT NULL COMMENT 'Address',
  zip           VARCHAR(20)  DEFAULT NULL COMMENT 'Zip',
  industry      VARCHAR(200) DEFAULT NULL COMMENT 'Industry',
  major_business VARCHAR(500) DEFAULT NULL COMMENT 'Major Business（英文一句话）',
  major_business_cn VARCHAR(500) DEFAULT NULL COMMENT '主营业务（中文）',
  company_size  VARCHAR(50)  DEFAULT NULL COMMENT 'Company Size（如 51-200）',
  customer_type VARCHAR(100) DEFAULT NULL COMMENT 'Customer Type（本次归类）',
  icp_score     INT          DEFAULT 0 COMMENT 'ICP Score 0-100',
  competitor    VARCHAR(200) DEFAULT NULL COMMENT 'Competitor（在用竞品）',
  source        VARCHAR(300) DEFAULT NULL COMMENT 'Source（发现来源 URL）',
  verification_status VARCHAR(50) DEFAULT 'unverified' COMMENT 'Verification Status',
  search_date   DATE         DEFAULT NULL COMMENT 'Search Date',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      DEFAULT 0,
  KEY idx_lhc_session (session_id),
  KEY idx_lhc_domain (domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '全球获客-公司';

-- 3) 联系人表
CREATE TABLE IF NOT EXISTS lead_hunt_contact (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id    BIGINT       NOT NULL,
  company_id    BIGINT       DEFAULT NULL COMMENT '关联 lead_hunt_company.id',
  company_name  VARCHAR(200) DEFAULT NULL COMMENT '冗余公司名（导出方便）',
  person_name   VARCHAR(150) DEFAULT NULL COMMENT 'Person',
  title         VARCHAR(200) DEFAULT NULL COMMENT 'Title',
  email         VARCHAR(200) DEFAULT NULL COMMENT 'Email',
  email_status  VARCHAR(30)  DEFAULT 'unverified' COMMENT 'verified / accept-all / unverified / invalid',
  phone         VARCHAR(100) DEFAULT NULL COMMENT 'Phone',
  contact_source VARCHAR(200) DEFAULT NULL COMMENT 'Contact Source（hunter / web）',
  contact_score INT          DEFAULT 0 COMMENT 'Contact Score 0-100',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      DEFAULT 0,
  KEY idx_lht_session (session_id),
  KEY idx_lht_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '全球获客-联系人';
