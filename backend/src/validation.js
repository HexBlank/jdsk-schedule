const crypto = require('node:crypto')

const SHARE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'

function httpError(statusCode, code, message) {
  const error = new Error(message)
  error.statusCode = statusCode
  error.code = code
  return error
}

function cleanText(value, name, maxLength, required = true) {
  const text = typeof value === 'string' ? value.trim() : ''
  if (required && !text) throw httpError(400, 'INVALID_ARGUMENT', `${name}不能为空`)
  if (text.length > maxLength) throw httpError(400, 'INVALID_ARGUMENT', `${name}不能超过 ${maxLength} 个字符`)
  return text
}

function normalizeWeeks(value) {
  if (!Array.isArray(value)) return []
  return [...new Set(value.map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 30))]
    .sort((a, b) => a - b)
}

function stableCourseId(course) {
  const canonical = [course.name, course.teacher, course.position]
    .map((value) => String(value || '').trim().toLocaleLowerCase('en-US'))
    .concat([course.day, course.startSection, course.endSection].map(String))
    .join('\u001f')
  return `course-${crypto.createHash('sha256').update(canonical, 'utf8').digest('hex').slice(0, 24)}`
}

function normalizeCourse(course, index) {
  if (!course || typeof course !== 'object') {
    throw httpError(400, 'INVALID_COURSE', `第 ${index + 1} 条课程格式错误`)
  }
  const day = Number(course.day)
  const startSection = Number(course.startSection)
  const endSection = Number(course.endSection)
  const weeks = normalizeWeeks(course.weeks)
  if (!Number.isInteger(day) || day < 1 || day > 7) {
    throw httpError(400, 'INVALID_COURSE', `第 ${index + 1} 条课程星期值错误`)
  }
  if (!Number.isInteger(startSection) || !Number.isInteger(endSection) || startSection < 1 || endSection > 12 || endSection < startSection) {
    throw httpError(400, 'INVALID_COURSE', `第 ${index + 1} 条课程节次错误`)
  }
  if (!weeks.length) throw httpError(400, 'INVALID_COURSE', `第 ${index + 1} 条课程没有有效周次`)
  const normalized = {
    name: cleanText(course.name, '课程名', 80),
    teacher: cleanText(course.teacher, '教师', 60, false),
    position: cleanText(course.position, '地点', 80, false),
    day,
    startSection,
    endSection,
    weeks
  }
  normalized.id = typeof course.id === 'string' && /^[A-Za-z0-9._:-]{1,80}$/.test(course.id)
    ? course.id
    : stableCourseId(normalized)
  return normalized
}

function normalizeAdjustment(input, schedule, replacingId) {
  if (!input || typeof input !== 'object') throw httpError(400, 'INVALID_ARGUMENT', '调课数据不能为空')
  const courseId = cleanText(input.courseId, '原课程', 80)
  const course = schedule.courses.find((item) => item.id === courseId)
  if (!course) throw httpError(400, 'COURSE_NOT_FOUND', '原课程已不存在，请先更新课表后重试')

  const sourceWeek = Number(input.sourceWeek)
  const sourceDay = Number(input.sourceDay)
  const sourceStartSection = Number(input.sourceStartSection)
  const sourceEndSection = Number(input.sourceEndSection)
  const targetWeek = Number(input.targetWeek)
  const targetDay = Number(input.targetDay)
  const targetStartSection = Number(input.targetStartSection)
  const targetEndSection = Number(input.targetEndSection)
  if (!Number.isInteger(sourceWeek) || !course.weeks.includes(sourceWeek) ||
      sourceDay !== course.day || sourceStartSection !== course.startSection || sourceEndSection !== course.endSection) {
    throw httpError(409, 'SOURCE_CHANGED', '原课程位置已经变化，请重新选择')
  }
  if (!Number.isInteger(targetWeek) || targetWeek < 1 || targetWeek > schedule.totalWeeks) {
    throw httpError(400, 'INVALID_ARGUMENT', '目标周次超出学期范围')
  }
  if (!Number.isInteger(targetDay) || targetDay < 1 || targetDay > 7 ||
      !Number.isInteger(targetStartSection) || !Number.isInteger(targetEndSection) ||
      targetStartSection < 1 || targetEndSection > 12 || targetEndSection < targetStartSection) {
    throw httpError(400, 'INVALID_ARGUMENT', '目标星期或节次无效')
  }
  const targetPosition = cleanText(input.targetPosition, '目标教室', 80, false) || null
  if (sourceWeek === targetWeek && sourceDay === targetDay &&
      sourceStartSection === targetStartSection && sourceEndSection === targetEndSection && !targetPosition) {
    throw httpError(400, 'INVALID_ARGUMENT', '目标时间与原课程相同')
  }
  if ((schedule.adjustments || []).some((item) => item.id !== replacingId &&
      item.courseId === courseId && item.sourceWeek === sourceWeek)) {
    throw httpError(409, 'ADJUSTMENT_EXISTS', '这次课程已经调过课，可在详情中修改原调课')
  }
  return {
    courseId,
    sourceWeek,
    sourceDay,
    sourceStartSection,
    sourceEndSection,
    targetWeek,
    targetDay,
    targetStartSection,
    targetEndSection,
    targetPosition,
    courseSnapshot: course
  }
}

function normalizeHoliday(input, schedule) {
  if (!input || typeof input !== 'object') throw httpError(400, 'INVALID_ARGUMENT', '停课数据不能为空')
  const week = Number(input.week)
  const day = Number(input.day)
  if (!Number.isInteger(week) || week < 1 || week > schedule.totalWeeks) {
    throw httpError(400, 'INVALID_ARGUMENT', '停课周次超出学期范围')
  }
  if (!Number.isInteger(day) || day < 1 || day > 7) {
    throw httpError(400, 'INVALID_ARGUMENT', '停课星期无效')
  }
  if ((schedule.holidays || []).some((item) => item.week === week && item.day === day)) {
    throw httpError(409, 'HOLIDAY_EXISTS', '这一天已经设置为停课')
  }
  if ((schedule.makeups || []).some((item) => item.targetWeek === week && item.targetDay === day)) {
    throw httpError(409, 'MAKEUP_CONFLICT', '这一天已安排补课，请先撤销补课')
  }
  return { week, day }
}

function normalizeMakeup(input, schedule) {
  if (!input || typeof input !== 'object') throw httpError(400, 'INVALID_ARGUMENT', '补课数据不能为空')
  const sourceWeek = Number(input.sourceWeek)
  const sourceDay = Number(input.sourceDay)
  const targetWeek = Number(input.targetWeek)
  const targetDay = Number(input.targetDay)
  const validWeek = (value) => Number.isInteger(value) && value >= 1 && value <= schedule.totalWeeks
  const validDay = (value) => Number.isInteger(value) && value >= 1 && value <= 7
  if (!validWeek(sourceWeek) || !validWeek(targetWeek) || !validDay(sourceDay) || !validDay(targetDay)) {
    throw httpError(400, 'INVALID_ARGUMENT', '补课周次或星期无效')
  }
  if (sourceWeek === targetWeek && sourceDay === targetDay) {
    throw httpError(400, 'INVALID_ARGUMENT', '补课日不能与来源是同一天')
  }
  if ((schedule.makeups || []).some((item) => item.targetWeek === targetWeek && item.targetDay === targetDay)) {
    throw httpError(409, 'MAKEUP_EXISTS', '这一天已经安排了补课')
  }
  if ((schedule.makeups || []).some((item) => item.sourceWeek === sourceWeek && item.sourceDay === sourceDay)) {
    throw httpError(409, 'MAKEUP_SOURCE_EXISTS', '来源日已有补课安排')
  }
  if ((schedule.holidays || []).some((item) => item.week === targetWeek && item.day === targetDay)) {
    throw httpError(409, 'HOLIDAY_CONFLICT', '这一天已设为停课，请先撤销停课')
  }
  return { sourceWeek, sourceDay, targetWeek, targetDay }
}

function normalizeCourseColors(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw httpError(400, 'INVALID_ARGUMENT', '课程颜色数据不能为空')
  }
  const colors = input.colors
  if (!colors || typeof colors !== 'object' || Array.isArray(colors)) {
    throw httpError(400, 'INVALID_ARGUMENT', '课程颜色数据不能为空')
  }
  const entries = Object.entries(colors)
  if (entries.length > 200) throw httpError(400, 'INVALID_ARGUMENT', '课程颜色不能超过 200 条')
  const normalized = {}
  for (const [name, value] of entries) {
    const key = cleanText(name, '课程名', 80)
    if (typeof value !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(value.trim())) {
      throw httpError(400, 'INVALID_ARGUMENT', `课程「${key}」的颜色值无效`)
    }
    normalized[key] = value.trim().toUpperCase()
  }
  return normalized
}
function normalizeSchedule(input) {
  if (!input || typeof input !== 'object') throw httpError(400, 'INVALID_ARGUMENT', '课表数据不能为空')
  if (!Array.isArray(input.courses) || input.courses.length < 1 || input.courses.length > 300) {
    throw httpError(400, 'INVALID_ARGUMENT', '课程数量必须在 1 到 300 条之间')
  }
  const semesterStart = cleanText(input.semesterStart, '开学日期', 10)
  const dateParts = semesterStart.split('-').map(Number)
  const exactDate = new Date(dateParts[0], dateParts[1] - 1, dateParts[2], 12)
  const dateIsExact = exactDate.getFullYear() === dateParts[0] && exactDate.getMonth() === dateParts[1] - 1 && exactDate.getDate() === dateParts[2]
  if (!/^\d{4}-\d{2}-\d{2}$/.test(semesterStart) || !dateIsExact) {
    throw httpError(400, 'INVALID_ARGUMENT', '开学日期格式必须为 YYYY-MM-DD')
  }
  const totalWeeks = Number(input.totalWeeks || 20)
  if (!Number.isInteger(totalWeeks) || totalWeeks < 1 || totalWeeks > 30) {
    throw httpError(400, 'INVALID_ARGUMENT', '总周数必须在 1 到 30 之间')
  }
  const timeSlots = Array.isArray(input.timeSlots)
    ? input.timeSlots.slice(0, 12).map((slot, index) => ({
        number: index + 1,
        startTime: cleanText(slot && slot.startTime, '开始时间', 5, false),
        endTime: cleanText(slot && slot.endTime, '结束时间', 5, false)
      }))
    : []
  const courses = input.courses.map(normalizeCourse)
  if (courses.some((course) => course.weeks.some((week) => week > totalWeeks))) {
    throw httpError(400, 'INVALID_COURSE', '课程周次不能超过学期总周数')
  }
  return {
    name: cleanText(input.name || '我的课表', '课表名称', 40),
    school: '',
    semesterStart,
    totalWeeks,
    timeSlots,
    courses
  }
}

function normalizeShareCode(value) {
  const code = String(value || '').trim().toUpperCase().replace(/\s/g, '')
  if (!/^[2-9A-HJ-NP-Z]{6,12}$/.test(code)) {
    throw httpError(400, 'INVALID_SHARE_CODE', '分享码格式不正确')
  }
  return code
}

// 情侣邀请码与分享码同一套字符和长度：猜中别人的邀请码就等于看到陌生人的课表，码空间不能小。
function normalizeInviteCode(value) {
  const code = String(value || '').trim().toUpperCase().replace(/\s/g, '')
  if (!/^[2-9A-HJ-NP-Z]{8}$/.test(code)) {
    throw httpError(400, 'INVALID_INVITE_CODE', '邀请码格式不正确')
  }
  return code
}

const COUPLE_NICKNAME_MAX = 8

/** 情侣双方的名字和颜色；只校验传了的字段，至少要传一项。 */
function normalizeCoupleProfile(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw httpError(400, 'INVALID_ARGUMENT', '名字和颜色不能为空')
  }
  const result = {}
  if (input.nickname !== undefined) {
    if (typeof input.nickname !== 'string') throw httpError(400, 'INVALID_ARGUMENT', '名字格式不正确')
    const nickname = input.nickname.trim()
    // 按字符（码点）计长度：中文、emoji 都算一个字，与客户端输入框限制一致
    if (Array.from(nickname).length > COUPLE_NICKNAME_MAX) {
      throw httpError(400, 'INVALID_ARGUMENT', `名字不能超过 ${COUPLE_NICKNAME_MAX} 个字`)
    }
    if (/[\u0000-\u001f\u007f]/.test(nickname)) throw httpError(400, 'INVALID_ARGUMENT', '名字不能包含控制字符')
    result.nickname = nickname
  }
  if (input.color !== undefined) {
    if (typeof input.color !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(input.color.trim())) {
      throw httpError(400, 'INVALID_ARGUMENT', '颜色值无效')
    }
    result.color = input.color.trim().toUpperCase()
  }
  if (result.nickname === undefined && result.color === undefined) {
    throw httpError(400, 'INVALID_ARGUMENT', '名字和颜色至少要改一项')
  }
  return result
}

function randomShareCode(length = 8) {
  let result = ''
  for (let index = 0; index < length; index += 1) {
    result += SHARE_ALPHABET[crypto.randomInt(SHARE_ALPHABET.length)]
  }
  return result
}

module.exports = {
  httpError,
  normalizeAdjustment,
  normalizeHoliday,
  normalizeMakeup,
  normalizeSchedule,
  normalizeCourseColors,
  normalizeCoupleProfile,
  normalizeInviteCode,
  normalizeShareCode,
  randomShareCode,
  stableCourseId
}
