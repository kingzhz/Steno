package com.snote.core.db

import android.database.sqlite.SQLiteDatabase

/** 检索索引实际使用的引擎（首次建库时探测得出）。 */
enum class SearchEngine { FTS5, FTS4 }

/**
 * 知识库表结构。
 *
 * 两层，界线不可破：
 *   源数据层 —— 用户内容，永不删除、永不压缩；
 *   派生物层 —— 全部可重建，关功能时整表删除。
 *
 * 另一个原则：DB 只存附件路径与哈希，原文件落在 files/attachments/。
 * 这样 DB 体积与附件体积解耦，DB 永远几十 MB 封顶。
 */
object KbSchema {

    const val DB_NAME = "kb.db"

    /**
     * v2：doc 增加 body_md（用户手写的原文），card 由它切分而来。
     * v3：新增对话历史（chat_session / chat_message）。
     * v4：新增待审写入（pending_op）。
     * v5：chat_message 增加 op_id。待审卡片必须记得自己指的是哪条待审项，
     *     否则切页重载后无法判断它是否已被处理（见 ChatScreen 里 pendingIds 的注释）。
     * v6：chat_message 增加 thumb_json。历史里要回显图片，但存不起原图
     *     （一张约 200KB），所以存缩略图（约 5KB）—— 见 ChatLine.thumbs 的注释。
     * v7：新增全局记忆（memory）。
     */
    const val DB_VERSION = 7

    /**
     * 对话历史 DDL。
     *
     * 抽成常量是因为**新建库（SOURCE_DDL）与升级库（MIGRATIONS[3]）都要用它**。
     * 同一份 DDL 抄两遍，迟早会有一边漏改 —— 表现就是「新装能用、升级后缺表」，
     * 而这台机器上跑的是升级路径，最先炸的正是自己。
     *
     * 对话属**源数据**（用户自己说的话），不是派生物：关掉 agent 功能只是让入口
     * 不可用，绝不删这里的行。
     */
    val CHAT_DDL: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS chat_session (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        // op_id：role='queued' 的行指向 pending_op.id，其余行为 0。
        // 存下来的意义：从历史重载这张卡片时，要能查 pending_op 判断它是否还活着 ——
        // 不存的话重载后一律当成「已处理」，明明还没审的待审项也会失去按钮。
        // thumb_json：这条消息带的图片缩略图（data URL 的 JSON 数组字符串）。
        // 存的不是原图 —— 原图一张约 200KB，历史里存不起；缩略图约 5KB。
        """CREATE TABLE IF NOT EXISTS chat_message (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
            ord INTEGER NOT NULL DEFAULT 0,
            role TEXT NOT NULL,
            content TEXT NOT NULL DEFAULT '',
            op_id INTEGER NOT NULL DEFAULT 0,
            thumb_json TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_chat_msg ON chat_message(session_id, ord)"
    )

    /**
     * 待审写入 DDL。
     *
     * agent 提出写操作时不立刻落库，而是把「工具名 + 参数」原样存下来等用户点同意。
     * 存的是**调用本身**而不是执行结果：这样审批和执行走的是同一条代码路径，
     * 不会出现「预览说改 A、实际改了 B」。
     *
     * 属源数据：待审项是用户的待办决策，重启后必须还在。
     */
    val PENDING_DDL: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS pending_op (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL DEFAULT 0,
            tool TEXT NOT NULL,
            args_json TEXT NOT NULL DEFAULT '{}',
            summary TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_pending_session ON pending_op(session_id, created_at)"
    )

    /**
     * 全局记忆 DDL。
     *
     * ## 为什么是一张独立的表而不是复用 doc/card
     *
     * 记忆与笔记的**生命周期完全不同**：笔记是你要它才存在，记忆是每一轮对话
     * 都要注入 system 提示词的。混进卡片表会让「什么该注入」变成一个要靠约定
     * 维持的隐规则 —— 而表本身能表达的东西，不该靠约定。
     *
     * ## why source 列
     *
     * 用户要能一眼分清「哪条是我写的、哪条是模型自己记的」：模型记错了必须能
     * 快速揪出来。这不是元数据洁癖，是可用性。
     *
     * ## 属源数据
     *
     * 记忆是用户与模型共同积累的资产，**关掉功能只让入口不可用，绝不删行**
     * （与 chat_session 同理）。它也不登记 derivedKinds。
     */
    val MEMORY_DDL: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS memory (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            text TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'user',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )"""
    )

    /** 源数据层 DDL。 */
    val SOURCE_DDL: List<String> = listOf(
        // body_md 是「用户手写的原文」，是笔记的唯一真相源；
        // card 保存的是它按 ## 分节切出来的结果，供卡片视图与检索使用。
        """CREATE TABLE IF NOT EXISTS doc (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            body_md TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL DEFAULT 'note',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",

        // 卡片 = doc 内的一个 section，是知识的最小原子单位。
        """CREATE TABLE IF NOT EXISTS card (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            doc_id INTEGER NOT NULL REFERENCES doc(id) ON DELETE CASCADE,
            ord INTEGER NOT NULL DEFAULT 0,
            title TEXT NOT NULL,
            body_md TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_card_doc ON card(doc_id, ord)",

        // 双链：hard = 显式 [[链接]]；mention = 正文提及但未建链。
        """CREATE TABLE IF NOT EXISTS link (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            src_card INTEGER NOT NULL REFERENCES card(id) ON DELETE CASCADE,
            dst_card INTEGER NOT NULL REFERENCES card(id) ON DELETE CASCADE,
            kind TEXT NOT NULL DEFAULT 'hard',
            anchor TEXT
        )""",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_link ON link(src_card, dst_card, kind)",
        "CREATE INDEX IF NOT EXISTS idx_link_dst ON link(dst_card)",

        // 别名：既是 jieba 自定义词典来源，也是侧边栏跳转匹配源。
        """CREATE TABLE IF NOT EXISTS alias (
            card_id INTEGER NOT NULL REFERENCES card(id) ON DELETE CASCADE,
            text TEXT NOT NULL
        )""",
        "CREATE INDEX IF NOT EXISTS idx_alias_text ON alias(text)",

        "CREATE TABLE IF NOT EXISTS tag (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)",
        """CREATE TABLE IF NOT EXISTS card_tag (
            card_id INTEGER NOT NULL REFERENCES card(id) ON DELETE CASCADE,
            tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
            PRIMARY KEY (card_id, tag_id)
        ) WITHOUT ROWID""",

        // 附件：只存路径与哈希，不存 BLOB。sha256 唯一 => 天然去重。
        """CREATE TABLE IF NOT EXISTS attachment (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sha256 TEXT NOT NULL UNIQUE,
            rel_path TEXT NOT NULL,
            bytes INTEGER NOT NULL,
            mime TEXT,
            page_count INTEGER,
            created_at INTEGER NOT NULL
        )""",

        // 卡片 -> 原文证据。答案必须能点回原文，否则无法判断有没有编。
        """CREATE TABLE IF NOT EXISTS evidence (
            card_id INTEGER NOT NULL REFERENCES card(id) ON DELETE CASCADE,
            attachment_id INTEGER NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,
            locator TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (card_id, attachment_id, locator)
        ) WITHOUT ROWID""",

        "CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",

        // 派生物账本：记录「哪个源 / 什么哈希 / 哪个生成器版本」。
        // 重建时只处理源变了或版本旧的条目，避免每次开关全量重算（尤其花钱的 OCR）。
        """CREATE TABLE IF NOT EXISTS derived (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            source_key TEXT NOT NULL,
            source_hash TEXT NOT NULL,
            gen_version INTEGER NOT NULL,
            built_at INTEGER NOT NULL
        )""",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_derived ON derived(kind, source_key)"
    ) + CHAT_DDL + PENDING_DDL + MEMORY_DDL

    /**
     * 版本迁移脚本：key = 目标版本号。
     *
     * 只在升级路径执行；全新安装直接走 SOURCE_DDL（已包含新列），不会重复跑这里。
     */
    val MIGRATIONS: Map<Int, List<String>> = mapOf(
        2 to listOf("ALTER TABLE doc ADD COLUMN body_md TEXT NOT NULL DEFAULT ''"),
        // 复用 CHAT_DDL，不另抄一份 —— 见 CHAT_DDL 的注释。
        3 to CHAT_DDL,
        4 to PENDING_DDL,
        // 复用同一份 DDL，不另抄一遍（见 CHAT_DDL 的注释）。
        7 to MEMORY_DDL
        // v5 加列刻意**不写在这里**，改走下面的 ensureColumns。
        //
        // 理由是列的存在性在升级路径之间不一致：CHAT_DDL 被「新建库」与
        // MIGRATIONS[3] 复用，而 CREATE TABLE IF NOT EXISTS 对已存在的旧表整段
        // 跳过 —— 老库升上来时表是旧的（无 op_id），全新库建出来时表是新的（有
        // op_id）。一句无条件的 ALTER 要成立，就得先断言「此刻表一定没有这一列」，
        // 而这个断言随升级起点变化，靠代码审查维持不住。先查再加没有这个前提。
    )

    /**
     * 幂等补列，升级路径专用。
     *
     * 只在「表已经存在、但缺这一列」时才 ALTER，因此从任何旧版本升上来都只加一次。
     */
    fun ensureColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, "chat_message", "op_id", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "chat_message", "thumb_json", "TEXT NOT NULL DEFAULT ''")
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        spec: String,
    ) {
        var exists = false
        // PRAGMA table_info 的列序：cid, name, type, notnull, dflt_value, pk。
        db.rawQuery("PRAGMA table_info(" + table + ")", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == column) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + spec)
    }

    /** 建全部源数据表 + 检索索引，返回探测到的索引引擎。 */
    fun createAll(db: SQLiteDatabase): SearchEngine {
        SOURCE_DDL.forEach { db.execSQL(it) }
        return createSearchIndex(db)
    }

    /**
     * FTS5 优先、FTS4 兜底。
     *
     * Android 内置 SQLite 是否编译了 FTS5 随系统版本而异，不能假定一定可用；
     * 因此先用一次性探针表实测，再决定建哪种虚表。
     * 索引列写入的是「预切好的 bigram token 串」，所以两种引擎都不依赖分词器能力。
     */
    fun createSearchIndex(db: SQLiteDatabase): SearchEngine {
        val engine = if (fts5Available(db)) SearchEngine.FTS5 else SearchEngine.FTS4
        val ddl = if (engine == SearchEngine.FTS5) {
            "CREATE VIRTUAL TABLE IF NOT EXISTS fts_card USING fts5(title, body_tok, tokenize='unicode61')"
        } else {
            "CREATE VIRTUAL TABLE IF NOT EXISTS fts_card USING fts4(title, body_tok)"
        }
        db.execSQL(ddl)
        return engine
    }

    private fun fts5Available(db: SQLiteDatabase): Boolean = try {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS _fts5_probe USING fts5(x)")
        db.execSQL("DROP TABLE IF EXISTS _fts5_probe")
        true
    } catch (t: Throwable) {
        false
    }

    /** 关功能时卸载的派生物对象。注意：全部可再生，源数据一律不在其中。 */
    val DROP_FTS = listOf("DROP TABLE IF EXISTS fts_card")
}
