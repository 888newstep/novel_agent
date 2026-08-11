-- =============================================
-- 创建数据库
-- =============================================
CREATE DATABASE IF NOT EXISTS novel_agent 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE novel_agent;

-- =============================================
-- 1. 小说基本信息
-- =============================================
CREATE TABLE novels (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    genre       VARCHAR(50),
    world_setting TEXT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 2. 角色设定
-- =============================================
CREATE TABLE characters (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    name        VARCHAR(50) NOT NULL,
    identity    VARCHAR(100),
    realm       VARCHAR(50),
    talent      VARCHAR(20) DEFAULT '下品'    COMMENT '资质：下品/中品/上品/极品/天品',
    element     VARCHAR(50)                   COMMENT '灵根属性，多个逗号分隔，如"火,雷"',
    element_main VARCHAR(20)                  COMMENT '主灵根/主属性',
    personality TEXT,
    backstory   TEXT,
    status      VARCHAR(20) DEFAULT 'alive'   COMMENT 'alive/dead/missing',
    first_appear INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_novel (novel_id),
    INDEX idx_talent (talent),
    INDEX idx_element (element),
    FOREIGN KEY (novel_id) REFERENCES novels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 3. 宗门/势力
-- =============================================
CREATE TABLE factions (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    rank        VARCHAR(50)                   COMMENT '势力等级：不入流/三流/二流/一流/顶级/圣地',
    talent      VARCHAR(20) DEFAULT '下品'    COMMENT '势力底蕴：下品/中品/上品/顶级/圣地',
    element     VARCHAR(50)                   COMMENT '势力属性偏向',
    category    VARCHAR(50)                   COMMENT '类型：宗门/家族/魔教/散修组织/商会',
    leader_id   BIGINT,
    location    VARCHAR(200),
    description TEXT,
    creed       TEXT,
    status      VARCHAR(20) DEFAULT 'active'  COMMENT 'active/declining/destroyed/hidden',
    first_appear INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_novel (novel_id),
    INDEX idx_leader (leader_id),
    INDEX idx_rank (rank),
    FOREIGN KEY (novel_id) REFERENCES novels(id),
    FOREIGN KEY (leader_id) REFERENCES characters(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 4. 势力成员关系（角色 ↔ 势力）
-- =============================================
CREATE TABLE faction_members (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id     BIGINT NOT NULL,
    faction_id   BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    role         VARCHAR(50)                   COMMENT '掌门/长老/护法/堂主/内门弟子/外门弟子',
    join_chapter INT,
    leave_chapter INT,
    leave_reason VARCHAR(100)                  COMMENT '叛逃/逐出/毕业/解散',
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_faction (faction_id),
    INDEX idx_character (character_id),
    UNIQUE KEY uk_faction_member (faction_id, character_id, leave_chapter),
    FOREIGN KEY (novel_id) REFERENCES novels(id),
    FOREIGN KEY (faction_id) REFERENCES factions(id) ON DELETE CASCADE,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 5. 法宝
-- =============================================
CREATE TABLE artifacts (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    talent      VARCHAR(20) DEFAULT '下品'    COMMENT '品质：下品/中品/上品/极品/仙品',
    element     VARCHAR(50)                   COMMENT '属性：火/冰/雷/空间等',
    rank        VARCHAR(50)                   COMMENT '品阶：法器/灵器/宝器/道器',
    type        VARCHAR(50)                   COMMENT '类型：攻击/防御/辅助/空间/飞行',
    owner_id    BIGINT,
    description TEXT,
    origin      TEXT,
    status      VARCHAR(20) DEFAULT 'active'  COMMENT 'active/destroyed/lost/sealed',
    first_appear INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_novel (novel_id),
    INDEX idx_owner (owner_id),
    INDEX idx_talent (talent),
    FOREIGN KEY (novel_id) REFERENCES novels(id),
    FOREIGN KEY (owner_id) REFERENCES characters(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 6. 功法
-- =============================================
CREATE TABLE skills (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    talent      VARCHAR(20) DEFAULT '下品'    COMMENT '品级：下品/中品/上品/绝品/神品',
    element     VARCHAR(50)                   COMMENT '属性：火系/水系/雷系等',
    rank        VARCHAR(50)                   COMMENT '品阶：凡阶/地阶/天阶/神阶',
    type        VARCHAR(50)                   COMMENT '类型：功法/法术/体术/阵法',
    owner_id    BIGINT,
    stage       VARCHAR(50) DEFAULT '入门'    COMMENT '修炼阶段：入门/小成/大成/圆满/化境',
    description TEXT,
    first_appear INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_novel (novel_id),
    INDEX idx_owner (owner_id),
    INDEX idx_talent (talent),
    FOREIGN KEY (novel_id) REFERENCES novels(id),
    FOREIGN KEY (owner_id) REFERENCES characters(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 7. 章节记录
-- =============================================
CREATE TABLE chapters (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    chapter_num INT NOT NULL,
    title       VARCHAR(200),
    summary     TEXT,
    key_events  TEXT                          COMMENT '关键事件简述，JSON数组',
    word_count  INT DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_novel_chapter (novel_id, chapter_num),
    UNIQUE KEY uk_novel_chapter (novel_id, chapter_num),
    FOREIGN KEY (novel_id) REFERENCES novels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 8. 关键事件/伏笔
-- =============================================
CREATE TABLE key_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    chapter_num INT NOT NULL,
    event_type  VARCHAR(30) NOT NULL          COMMENT 'plot_hook(伏笔)/plot_twist(转折)/foreshadowing(预兆)/climax(高潮)',
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    involved_characters TEXT                  COMMENT '涉及角色ID列表，JSON数组',
    involved_artifacts TEXT                   COMMENT '涉及法宝ID列表，JSON数组',
    resolved    BOOLEAN DEFAULT FALSE,
    resolved_at INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_novel_type (novel_id, event_type),
    INDEX idx_novel_unresolved (novel_id, resolved),
    FOREIGN KEY (novel_id) REFERENCES novels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 9. 人物/势力关系
-- =============================================
CREATE TABLE relations (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id      BIGINT NOT NULL,
    source_type   VARCHAR(20) NOT NULL        COMMENT 'character/faction',
    source_id     BIGINT NOT NULL,
    source_name   VARCHAR(100),
    target_type   VARCHAR(20) NOT NULL        COMMENT 'character/faction',
    target_id     BIGINT NOT NULL,
    target_name   VARCHAR(100),
    relation_type VARCHAR(50) NOT NULL        COMMENT '师徒/仇敌/盟友/恋人/父子/主仆/同门',
    description   TEXT,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_novel (novel_id),
    INDEX idx_source (source_type, source_id),
    INDEX idx_target (target_type, target_id),
    FOREIGN KEY (novel_id) REFERENCES novels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 10. 灵感库
-- =============================================
CREATE TABLE inspirations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT                         COMMENT 'NULL表示通用灵感',
    category    VARCHAR(50) NOT NULL           COMMENT '突破/战斗/法宝/秘境/情感/阴谋/拍卖',
    content     TEXT NOT NULL,
    tags        VARCHAR(500)                   COMMENT '标签，逗号分隔',
    source      VARCHAR(100)                   COMMENT '来源：manual/crawled/generated',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_novel_category (novel_id, category),
    FOREIGN KEY (novel_id) REFERENCES novels(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 11. 法宝/功法变更日志
-- =============================================
CREATE TABLE item_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    novel_id    BIGINT NOT NULL,
    item_type   VARCHAR(20) NOT NULL          COMMENT 'artifact/skill',
    item_id     BIGINT NOT NULL,
    chapter_num INT NOT NULL,
    action      VARCHAR(50) NOT NULL          COMMENT 'acquire/upgrade/lose/destroy/transfer',
    old_owner   BIGINT,
    new_owner   BIGINT,
    old_talent  VARCHAR(20)                   COMMENT '原资质/品质',
    new_talent  VARCHAR(20)                   COMMENT '新资质/品质',
    old_rank    VARCHAR(50),
    new_rank    VARCHAR(50),
    old_stage   VARCHAR(50),
    new_stage   VARCHAR(50),
    description TEXT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_novel_item (novel_id, item_type, item_id),
    INDEX idx_chapter (novel_id, chapter_num),
    FOREIGN KEY (novel_id) REFERENCES novels(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 12. RAG evaluation aggregate snapshots
-- Only scalar trend metrics are persisted; query details and novel text are excluded.
-- =============================================
CREATE TABLE rag_evaluation_snapshots (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_name                 VARCHAR(100) NOT NULL,
    dataset_version              VARCHAR(50) NOT NULL,
    novel_id                     BIGINT NOT NULL,
    top_k                        INT NOT NULL,
    query_count                  INT NOT NULL,
    queries_with_relevant_result INT NOT NULL,
    recall_at_k                  DOUBLE NOT NULL,
    precision_at_k               DOUBLE NOT NULL,
    mrr                          DOUBLE NOT NULL,
    avg_latency_ms               DOUBLE NOT NULL,
    p95_latency_ms               DOUBLE NOT NULL,
    p99_latency_ms               DOUBLE NOT NULL,
    min_latency_ms               DOUBLE NOT NULL,
    max_latency_ms               DOUBLE NOT NULL,
    keyword_coverage             DOUBLE NOT NULL,
    avg_context_chars            DOUBLE NOT NULL,
    avg_context_tokens           DOUBLE NOT NULL,
    evaluated_at                 BIGINT NOT NULL,
    created_at                   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_rag_eval_profile_novel_time (profile_name, novel_id, evaluated_at),
    INDEX idx_rag_eval_profile_time (profile_name, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
