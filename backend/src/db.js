const fs = require('node:fs')
const path = require('node:path')
const Database = require('better-sqlite3')

function openDatabase(filename) {
  if (filename !== ':memory:') {
    fs.mkdirSync(path.dirname(filename), { recursive: true })
  }
  const db = new Database(filename)
  db.pragma('journal_mode = WAL')
  db.pragma('foreign_keys = ON')
  db.pragma('busy_timeout = 5000')
  migrate(db)
  return db
}

function migrate(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      -- 身份主体标识（OIDC 意义上的 subject），不绑定任何第三方登录方式。
      -- 当前取值形如 app:v2:<HMAC(deviceId)>，由 device-auth.js 派生。
      -- 注意：这是线上用户的身份取值，改列名或改前缀会让已注册设备变成新用户。
      openid TEXT NOT NULL UNIQUE,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS schedules (
      id TEXT PRIMARY KEY,
      owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      name TEXT NOT NULL,
      school TEXT NOT NULL,
      semester_start TEXT NOT NULL,
      total_weeks INTEGER NOT NULL,
      data_json TEXT NOT NULL,
      share_code TEXT NOT NULL UNIQUE COLLATE NOCASE,
      revision INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS schedule_members (
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
      joined_at TEXT NOT NULL,
      PRIMARY KEY (user_id, schedule_id)
    );

    CREATE INDEX IF NOT EXISTS idx_schedules_owner ON schedules(owner_id);
    CREATE INDEX IF NOT EXISTS idx_schedules_updated ON schedules(updated_at DESC);
    CREATE INDEX IF NOT EXISTS idx_members_schedule ON schedule_members(schedule_id);

    -- 情侣绑定：一对情侣是同一 couple_id 下的两行，user_id 做主键保证一人只绑一对。
    -- current_schedule_id 是本人的当前课表（自己发布的或加入的），对方据此读取；
    -- 读取权限不写进 schedule_members，免得对方被算进订阅人数、解绑后还残留成员关系。
    -- 名字和颜色跟着人走，双方都能改；*_updated_by 记最后修改人，客户端据此提示「TA 改了你的名字」。
    CREATE TABLE IF NOT EXISTS couple_members (
      user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      couple_id TEXT NOT NULL,
      current_schedule_id TEXT REFERENCES schedules(id) ON DELETE SET NULL,
      nickname TEXT NOT NULL DEFAULT '',
      nickname_updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
      color TEXT NOT NULL,
      color_updated_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
      revision INTEGER NOT NULL DEFAULT 1,
      joined_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS couple_invites (
      code TEXT PRIMARY KEY COLLATE NOCASE,
      inviter_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
      expires_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_couple_members_couple ON couple_members(couple_id);
    CREATE INDEX IF NOT EXISTS idx_couple_members_schedule ON couple_members(current_schedule_id);
  `)
}

function now() {
  return new Date().toISOString()
}

function ensureUser(db, openid) {
  const timestamp = now()
  const staleBefore = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()
  db.prepare(`
    INSERT INTO users (openid, created_at, updated_at)
    VALUES (?, ?, ?)
    ON CONFLICT(openid) DO UPDATE SET updated_at = excluded.updated_at
    WHERE users.updated_at < ?
  `).run(openid, timestamp, timestamp, staleBefore)
  return db.prepare('SELECT id, openid FROM users WHERE openid = ?').get(openid)
}

/** 首次使用 HMAC 设备身份时，把旧版明文 deviceId 身份无损迁移到新身份。 */
function migrateLegacyDeviceUser(db, legacyOpenid, currentOpenid) {
  const legacy = db.prepare('SELECT id FROM users WHERE openid = ?').get(legacyOpenid)
  if (!legacy) return
  const current = db.prepare('SELECT id FROM users WHERE openid = ?').get(currentOpenid)
  if (!current || current.id === legacy.id) return
  db.transaction(() => {
    db.prepare('UPDATE schedules SET owner_id = ? WHERE owner_id = ?').run(current.id, legacy.id)
    db.prepare(`
      INSERT INTO schedule_members (user_id, schedule_id, joined_at)
      SELECT ?, schedule_id, joined_at FROM schedule_members WHERE user_id = ?
      ON CONFLICT(user_id, schedule_id) DO NOTHING
    `).run(current.id, legacy.id)
    db.prepare('DELETE FROM users WHERE id = ?').run(legacy.id)
  })()
}

module.exports = { openDatabase, ensureUser, migrateLegacyDeviceUser, now }
