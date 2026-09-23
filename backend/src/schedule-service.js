const crypto = require('node:crypto')
const { now } = require('./db')
const { httpError, normalizeAdjustment, normalizeCourseColors, normalizeHoliday, normalizeMakeup, normalizeSchedule, normalizeShareCode, randomShareCode, stableCourseId } = require('./validation')

function hydrateData(raw) {
  const data = raw && typeof raw === 'object' ? raw : {}
  const courses = Array.isArray(data.courses) ? data.courses.map((course) => ({
    ...course,
    id: course.id || stableCourseId(course)
  })) : []
  return {
    timeSlots: Array.isArray(data.timeSlots) ? data.timeSlots : [],
    courses,
    adjustments: Array.isArray(data.adjustments) ? data.adjustments.map((item) => ({
      ...item,
      courseId: item.courseId || (item.courseSnapshot && item.courseSnapshot.id),
      courseSnapshot: item.courseSnapshot ? {
        ...item.courseSnapshot,
        id: item.courseSnapshot.id || stableCourseId(item.courseSnapshot)
      } : courses.find((course) => course.id === item.courseId)
    })).filter((item) => item.id && item.courseId && item.courseSnapshot) : [],
    holidays: Array.isArray(data.holidays) ? data.holidays : [],
    makeups: Array.isArray(data.makeups) ? data.makeups : [],
    courseColors: data.courseColors && typeof data.courseColors === 'object' && !Array.isArray(data.courseColors)
      ? data.courseColors
      : {}
  }
}

function parseData(row) {
  const data = hydrateData(JSON.parse(row.data_json))
  return {
    id: row.id,
    name: row.name,
    school: row.school,
    semesterStart: row.semester_start,
    totalWeeks: row.total_weeks,
    shareCode: row.share_code,
    revision: row.revision,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    subscriberCount: Number(row.subscriber_count || 0),
    ...data
  }
}

function publicSummary(row, role) {
  return {
    id: row.id,
    name: row.name,
    school: row.school,
    semesterStart: row.semester_start,
    totalWeeks: row.total_weeks,
    shareCode: role === 'owner' ? row.share_code : undefined,
    revision: row.revision,
    updatedAt: row.updated_at,
    courseCount: JSON.parse(row.data_json).courses.length,
    subscriberCount: Number(row.subscriber_count || 0),
    role
  }
}

function createScheduleService(db, config = {}) {
  const withCounts = `
    SELECT s.*, (SELECT COUNT(*) FROM schedule_members sm WHERE sm.schedule_id = s.id) AS subscriber_count
    FROM schedules s
  `

  function getOwned(userId, scheduleId) {
    const row = db.prepare(`${withCounts} WHERE s.id = ? AND s.owner_id = ?`).get(scheduleId, userId)
    if (!row) throw httpError(404, 'SCHEDULE_NOT_FOUND', '课表不存在或你不是发布者')
    return row
  }

  function generateUniqueShareCode() {
    for (let tries = 0; tries < 12; tries += 1) {
      const code = randomShareCode()
      if (!db.prepare('SELECT 1 FROM schedules WHERE share_code = ?').get(code)) return code
    }
    throw httpError(503, 'SHARE_CODE_EXHAUSTED', '分享码生成失败，请稍后重试')
  }

  function list(userId) {
    const ownedRows = db.prepare(`${withCounts} WHERE s.owner_id = ? ORDER BY s.updated_at DESC`).all(userId)
    const joinedRows = db.prepare(`
      ${withCounts}
      JOIN schedule_members mine ON mine.schedule_id = s.id
      WHERE mine.user_id = ?
      ORDER BY s.updated_at DESC
    `).all(userId)
    return [
      ...ownedRows.map((row) => publicSummary(row, 'owner')),
      ...joinedRows.map((row) => publicSummary(row, 'subscriber'))
    ]
  }

  function get(userId, scheduleId) {
    const row = db.prepare(`
      ${withCounts}
      WHERE s.id = ? AND (
        s.owner_id = ? OR EXISTS (
          SELECT 1 FROM schedule_members mine WHERE mine.schedule_id = s.id AND mine.user_id = ?
        )
      )
    `).get(scheduleId, userId, userId)
    if (!row) throw httpError(404, 'SCHEDULE_NOT_FOUND', '课表不存在或尚未加入')
    const result = parseData(row)
    result.role = row.owner_id === userId ? 'owner' : 'subscriber'
    if (result.role !== 'owner') delete result.shareCode
    return result
  }

  function save(userId, scheduleId, input) {
    const schedule = normalizeSchedule(input)
    const timestamp = now()
    if (scheduleId) {
      const existing = getOwned(userId, scheduleId)
      const previousData = JSON.parse(existing.data_json)
      assertRevision(existing, input && input._expectedRevision)
      db.prepare(`
        UPDATE schedules SET name = ?, school = ?, semester_start = ?, total_weeks = ?,
          data_json = ?, revision = revision + 1, updated_at = ?
        WHERE id = ? AND owner_id = ?
      `).run(
        schedule.name,
        schedule.school,
        schedule.semesterStart,
        schedule.totalWeeks,
        JSON.stringify({
          timeSlots: schedule.timeSlots,
          courses: schedule.courses,
          // 整表 PUT 表示教务/导入快照覆盖；旧的手动调课例外不得污染权威数据。
          adjustments: [],
          // 手动课程颜色按课程名生效，重新导入后依然保留。
          courseColors: previousData.courseColors || {}
        }),
        timestamp,
        scheduleId,
        userId
      )
      return get(userId, existing.id)
    }

    const id = crypto.randomUUID()
    const ownedCount = db.prepare('SELECT COUNT(*) AS count FROM schedules WHERE owner_id = ?').get(userId).count
    if (ownedCount >= (config.maxSchedulesPerUser || 30)) {
      throw httpError(409, 'SCHEDULE_QUOTA_EXCEEDED', '已达到可发布课表数量上限')
    }
    db.prepare(`
      INSERT INTO schedules (
        id, owner_id, name, school, semester_start, total_weeks, data_json,
        share_code, revision, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
    `).run(
      id,
      userId,
      schedule.name,
      schedule.school,
      schedule.semesterStart,
      schedule.totalWeeks,
      JSON.stringify({ timeSlots: schedule.timeSlots, courses: schedule.courses, adjustments: [], courseColors: {} }),
      generateUniqueShareCode(),
      timestamp,
      timestamp
    )
    return get(userId, id)
  }

  function preview(userId, rawCode) {
    const code = normalizeShareCode(rawCode)
    const row = db.prepare(`${withCounts} WHERE s.share_code = ?`).get(code)
    if (!row) throw httpError(404, 'SHARE_CODE_NOT_FOUND', '没有找到这个分享码')
    return {
      id: row.id,
      name: row.name,
      school: row.school,
      semesterStart: row.semester_start,
      totalWeeks: row.total_weeks,
      courseCount: JSON.parse(row.data_json).courses.length,
      revision: row.revision,
      updatedAt: row.updated_at,
      subscriberCount: Number(row.subscriber_count || 0),
      joined: row.owner_id === userId || Boolean(db.prepare('SELECT 1 FROM schedule_members WHERE user_id = ? AND schedule_id = ?').get(userId, row.id)),
      owned: row.owner_id === userId
    }
  }

  function join(userId, rawCode) {
    const code = normalizeShareCode(rawCode)
    const row = db.prepare('SELECT id, owner_id FROM schedules WHERE share_code = ?').get(code)
    if (!row) throw httpError(404, 'SHARE_CODE_NOT_FOUND', '没有找到这个分享码')
    if (row.owner_id !== userId) {
      const alreadyJoined = db.prepare('SELECT 1 FROM schedule_members WHERE user_id = ? AND schedule_id = ?').get(userId, row.id)
      if (!alreadyJoined) {
        const joinedCount = db.prepare('SELECT COUNT(*) AS count FROM schedule_members WHERE user_id = ?').get(userId).count
        if (joinedCount >= (config.maxSubscriptionsPerUser || 100)) {
          throw httpError(409, 'SUBSCRIPTION_QUOTA_EXCEEDED', '已达到可加入课表数量上限')
        }
      }
      db.prepare(`
        INSERT INTO schedule_members (user_id, schedule_id, joined_at)
        VALUES (?, ?, ?)
        ON CONFLICT(user_id, schedule_id) DO NOTHING
      `).run(userId, row.id, now())
    }
    return get(userId, row.id)
  }

  // 退出幂等：成员关系已不存在（发布者删了课表、或早已退出）也视为成功。
  // 旧版客户端把 404 当作同步失败并无限重试，会卡住后续所有待同步写入。
  function leave(userId, scheduleId) {
    db.prepare('DELETE FROM schedule_members WHERE user_id = ? AND schedule_id = ?').run(userId, scheduleId)
  }

  // 课表在服务端的存在性与本人关系。客户端据此区分「发布者已删除」和「自己不在
  // 成员里了」，不能只凭列表里少了一项就下结论。课表 id 是随机 UUID，暴露存在性无风险。
  function status(userId, scheduleId) {
    const row = db.prepare('SELECT owner_id FROM schedules WHERE id = ?').get(scheduleId)
    if (!row) return { exists: false, owned: false, member: false }
    const owned = row.owner_id === userId
    const member = owned || Boolean(
      db.prepare('SELECT 1 FROM schedule_members WHERE user_id = ? AND schedule_id = ?').get(userId, scheduleId)
    )
    return { exists: true, owned, member }
  }

  function rotateCode(userId, scheduleId) {
    getOwned(userId, scheduleId)
    const shareCode = generateUniqueShareCode()
    db.prepare('UPDATE schedules SET share_code = ?, updated_at = ? WHERE id = ? AND owner_id = ?')
      .run(shareCode, now(), scheduleId, userId)
    return { shareCode }
  }

  function remove(userId, scheduleId) {
    // 课表已不存在说明删除目标已达成（幂等，理由同 leave）；存在但不是本人的仍然拒绝。
    if (!db.prepare('SELECT 1 FROM schedules WHERE id = ?').get(scheduleId)) return
    getOwned(userId, scheduleId)
    db.prepare('DELETE FROM schedules WHERE id = ? AND owner_id = ?').run(scheduleId, userId)
  }

  function createAdjustment(userId, scheduleId, input, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const normalized = normalizeAdjustment(input, { ...data, totalWeeks: row.total_weeks })
    const timestamp = now()
    data.adjustments.push({
      id: crypto.randomUUID(),
      ...normalized,
      createdAt: timestamp,
      updatedAt: timestamp
    })
    persistData(row, data, timestamp)
    return get(userId, scheduleId)
  }

  function updateAdjustment(userId, scheduleId, adjustmentId, input, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const index = data.adjustments.findIndex((item) => item.id === adjustmentId)
    if (index < 0) throw httpError(404, 'ADJUSTMENT_NOT_FOUND', '调课记录不存在')
    const normalized = normalizeAdjustment(input, { ...data, totalWeeks: row.total_weeks }, adjustmentId)
    data.adjustments[index] = {
      id: adjustmentId,
      ...normalized,
      createdAt: data.adjustments[index].createdAt,
      updatedAt: now()
    }
    persistData(row, data, data.adjustments[index].updatedAt)
    return get(userId, scheduleId)
  }

  function removeAdjustment(userId, scheduleId, adjustmentId, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const remaining = data.adjustments.filter((item) => item.id !== adjustmentId)
    if (remaining.length === data.adjustments.length) {
      throw httpError(404, 'ADJUSTMENT_NOT_FOUND', '调课记录不存在')
    }
    data.adjustments = remaining
    persistData(row, data, now())
    return get(userId, scheduleId)
  }

  function createHoliday(userId, scheduleId, input, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const normalized = normalizeHoliday(input, { ...data, totalWeeks: row.total_weeks })
    const timestamp = now()
    data.holidays.push({
      id: crypto.randomUUID(),
      ...normalized,
      createdAt: timestamp,
      updatedAt: timestamp
    })
    persistData(row, data, timestamp)
    return get(userId, scheduleId)
  }

  function removeHoliday(userId, scheduleId, holidayId, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const remaining = data.holidays.filter((item) => item.id !== holidayId)
    if (remaining.length === data.holidays.length) {
      throw httpError(404, 'HOLIDAY_NOT_FOUND', '停课记录不存在')
    }
    data.holidays = remaining
    persistData(row, data, now())
    return get(userId, scheduleId)
  }

  function createMakeup(userId, scheduleId, input, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const normalized = normalizeMakeup(input, { ...data, totalWeeks: row.total_weeks })
    const timestamp = now()
    data.makeups.push({
      id: crypto.randomUUID(),
      ...normalized,
      createdAt: timestamp,
      updatedAt: timestamp
    })
    persistData(row, data, timestamp)
    return get(userId, scheduleId)
  }

  function removeMakeup(userId, scheduleId, makeupId, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    const remaining = data.makeups.filter((item) => item.id !== makeupId)
    if (remaining.length === data.makeups.length) {
      throw httpError(404, 'MAKEUP_NOT_FOUND', '补课记录不存在')
    }
    data.makeups = remaining
    persistData(row, data, now())
    return get(userId, scheduleId)
  }

  function setCourseColors(userId, scheduleId, input, expectedRevision) {
    const row = getOwned(userId, scheduleId)
    assertRevision(row, expectedRevision)
    const data = hydrateData(JSON.parse(row.data_json))
    data.courseColors = normalizeCourseColors(input)
    persistData(row, data, now())
    return get(userId, scheduleId)
  }

  function persistData(row, data, timestamp) {
    db.prepare(`
      UPDATE schedules SET data_json = ?, revision = revision + 1, updated_at = ?
      WHERE id = ? AND owner_id = ?
    `).run(JSON.stringify(data), timestamp, row.id, row.owner_id)
  }

  function assertRevision(row, rawExpected) {
    const expected = Number(rawExpected)
    if (!Number.isInteger(expected)) {
      throw httpError(428, 'REVISION_REQUIRED', '缺少课表版本，请刷新后重试')
    }
    if (expected !== row.revision) {
      throw httpError(409, 'REVISION_CONFLICT', '课表已在其他设备更新，请刷新后重试')
    }
  }

  return {
    list, get, save, preview, join, leave, status, rotateCode, remove,
    createAdjustment, updateAdjustment, removeAdjustment,
    createHoliday, removeHoliday, createMakeup, removeMakeup, setCourseColors
  }
}

module.exports = { createScheduleService }
