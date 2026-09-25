const crypto = require('node:crypto')
const { now } = require('./db')
const { httpError, normalizeCoupleProfile, normalizeInviteCode, randomShareCode } = require('./validation')

const INVITE_TTL_MS = 24 * 60 * 60 * 1000
// 默认颜色：发邀请的人浅蓝、接受邀请的人浅粉，之后双方都能改
const INVITER_COLOR = '#7FAEE3'
const INVITEE_COLOR = '#F4AFC2'

/**
 * 情侣绑定。一对情侣是同一 couple_id 下的两行 couple_members；只剩一行（对方注销）即视为未绑定，
 * 读到时顺手清理。对方课表的读取权限每次实时判断：绑定还在，且对方仍是那份课表的发布者或成员。
 */
function createCoupleService(db, schedules) {
  function myRow(userId) {
    return db.prepare('SELECT * FROM couple_members WHERE user_id = ?').get(userId)
  }

  function partnerOf(row) {
    return db.prepare('SELECT * FROM couple_members WHERE couple_id = ? AND user_id <> ?').get(row.couple_id, row.user_id)
  }

  /** 本人与对方的记录；对方已不在（注销）时清掉自己这行，按未绑定处理。 */
  function pair(userId) {
    const me = myRow(userId)
    if (!me) return null
    const partner = partnerOf(me)
    if (!partner) {
      db.prepare('DELETE FROM couple_members WHERE user_id = ?').run(userId)
      return null
    }
    return { me, partner }
  }

  function requirePair(userId) {
    const result = pair(userId)
    if (!result) throw httpError(404, 'COUPLE_NOT_FOUND', '还没有绑定情侣')
    return result
  }

  function validInvite(userId) {
    const invite = db.prepare('SELECT code, expires_at FROM couple_invites WHERE inviter_id = ?').get(userId)
    if (!invite) return null
    if (invite.expires_at <= now()) {
      db.prepare('DELETE FROM couple_invites WHERE inviter_id = ?').run(userId)
      return null
    }
    return { code: invite.code, expiresAt: invite.expires_at }
  }

  function relative(updatedBy, viewerId) {
    if (updatedBy == null) return null
    return updatedBy === viewerId ? 'me' : 'partner'
  }

  function memberView(row, viewerId) {
    return {
      nickname: row.nickname,
      nicknameUpdatedBy: relative(row.nickname_updated_by, viewerId),
      color: row.color,
      colorUpdatedBy: relative(row.color_updated_by, viewerId),
      revision: row.revision,
      updatedAt: row.updated_at
    }
  }

  /** 对方当前课表的摘要：只给 id 和版本号，不给课表名；对方已无权访问时为 null。 */
  function partnerScheduleSummary(partner) {
    const scheduleId = partner.current_schedule_id
    if (!scheduleId || !schedules.status(partner.user_id, scheduleId).member) return null
    const row = db.prepare('SELECT id, revision, updated_at FROM schedules WHERE id = ?').get(scheduleId)
    return row ? { id: row.id, revision: row.revision, updatedAt: row.updated_at } : null
  }

  function view(userId) {
    const current = pair(userId)
    if (!current) return { bound: false, invite: validInvite(userId) }
    const { me, partner } = current
    const partnerSchedule = partnerScheduleSummary(partner)
    return {
      bound: true,
      coupleId: me.couple_id,
      since: me.joined_at < partner.joined_at ? me.joined_at : partner.joined_at,
      me: { ...memberView(me, userId), currentScheduleId: me.current_schedule_id },
      partner: {
        ...memberView(partner, userId),
        schedule: partnerSchedule,
        sameScheduleAsMine: Boolean(partnerSchedule && partnerSchedule.id === me.current_schedule_id)
      }
    }
  }

  function createInvite(userId) {
    if (pair(userId)) throw httpError(409, 'COUPLE_ALREADY_BOUND', '你已经绑定了情侣，先解除绑定才能再邀请')
    db.prepare('DELETE FROM couple_invites WHERE expires_at <= ?').run(now())
    let code = null
    for (let tries = 0; tries < 12 && !code; tries += 1) {
      const candidate = randomShareCode()
      if (!db.prepare('SELECT 1 FROM couple_invites WHERE code = ?').get(candidate)) code = candidate
    }
    if (!code) throw httpError(503, 'INVITE_CODE_EXHAUSTED', '邀请码生成失败，请稍后重试')
    const expiresAt = new Date(Date.now() + INVITE_TTL_MS).toISOString()
    db.transaction(() => {
      // 一个人同一时间只有一个有效邀请码，新码替换旧码
      db.prepare('DELETE FROM couple_invites WHERE inviter_id = ?').run(userId)
      db.prepare('INSERT INTO couple_invites (code, inviter_id, expires_at) VALUES (?, ?, ?)').run(code, userId, expiresAt)
    })()
    return { code, expiresAt }
  }

  function accept(userId, rawCode) {
    const code = normalizeInviteCode(rawCode)
    const invite = db.prepare('SELECT * FROM couple_invites WHERE code = ?').get(code)
    if (!invite || invite.expires_at <= now()) {
      throw httpError(404, 'INVITE_NOT_FOUND', '邀请码不存在或已过期')
    }
    if (invite.inviter_id === userId) throw httpError(400, 'INVITE_SELF', '不能接受自己的邀请码')
    db.transaction(() => {
      if (pair(userId)) throw httpError(409, 'COUPLE_ALREADY_BOUND', '你已经绑定了情侣，先解除绑定才能接受邀请')
      if (pair(invite.inviter_id)) throw httpError(409, 'INVITER_ALREADY_BOUND', '对方已经和别人绑定了')
      const coupleId = crypto.randomUUID()
      const timestamp = now()
      const insert = db.prepare(`
        INSERT INTO couple_members (user_id, couple_id, color, joined_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
      `)
      insert.run(invite.inviter_id, coupleId, INVITER_COLOR, timestamp, timestamp)
      insert.run(userId, coupleId, INVITEE_COLOR, timestamp, timestamp)
      // 双方手上的邀请码都作废（接受方可能也生成过自己的码）
      db.prepare('DELETE FROM couple_invites WHERE inviter_id IN (?, ?)').run(invite.inviter_id, userId)
    })()
    return view(userId)
  }

  function partnerSchedule(userId) {
    const { partner } = requirePair(userId)
    const summary = partnerScheduleSummary(partner)
    if (!summary) throw httpError(404, 'PARTNER_SCHEDULE_UNAVAILABLE', 'TA 当前没有可查看的课表')
    return schedules.readForPartner(summary.id)
  }

  /** 上报本人的当前课表；null 表示本机没有课表。只能是自己发布或加入的课表。 */
  function setCurrentSchedule(userId, rawScheduleId) {
    const { me } = requirePair(userId)
    const scheduleId = rawScheduleId == null || rawScheduleId === '' ? null : String(rawScheduleId)
    if (scheduleId && !schedules.status(userId, scheduleId).member) {
      throw httpError(404, 'SCHEDULE_NOT_FOUND', '课表不存在或尚未加入')
    }
    if (me.current_schedule_id !== scheduleId) {
      db.prepare(`
        UPDATE couple_members SET current_schedule_id = ?, revision = revision + 1, updated_at = ?
        WHERE user_id = ?
      `).run(scheduleId, now(), userId)
    }
    return view(userId)
  }

  /** 改名字或颜色。who = me | partner，双方都能改双方；值没变的字段不动，免得误触发改名提示。 */
  function updateMember(userId, who, input) {
    if (who !== 'me' && who !== 'partner') throw httpError(400, 'INVALID_ARGUMENT', '只能修改自己或 TA')
    const profile = normalizeCoupleProfile(input)
    const { me, partner } = requirePair(userId)
    const target = who === 'me' ? me : partner
    const nicknameChanged = profile.nickname !== undefined && profile.nickname !== target.nickname
    const colorChanged = profile.color !== undefined && profile.color !== target.color
    if (nicknameChanged || colorChanged) {
      db.prepare(`
        UPDATE couple_members SET
          nickname = ?, nickname_updated_by = ?,
          color = ?, color_updated_by = ?,
          revision = revision + 1, updated_at = ?
        WHERE user_id = ?
      `).run(
        nicknameChanged ? profile.nickname : target.nickname,
        nicknameChanged ? userId : target.nickname_updated_by,
        colorChanged ? profile.color : target.color,
        colorChanged ? userId : target.color_updated_by,
        now(),
        target.user_id
      )
    }
    return view(userId)
  }

  // 解绑幂等：已经不在绑定里（对方先解绑、或早已解绑）也视为成功，顺带作废自己的邀请码。
  function unbind(userId) {
    const me = myRow(userId)
    db.transaction(() => {
      if (me) db.prepare('DELETE FROM couple_members WHERE couple_id = ?').run(me.couple_id)
      db.prepare('DELETE FROM couple_invites WHERE inviter_id = ?').run(userId)
    })()
  }

  return { view, createInvite, accept, partnerSchedule, setCurrentSchedule, updateMember, unbind }
}

module.exports = { createCoupleService, INVITER_COLOR, INVITEE_COLOR }
