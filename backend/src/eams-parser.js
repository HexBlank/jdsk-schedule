// 教务系统（EAMS）课表解析器。
// 算法思路参考开源项目「拾光课程表」（Apache-2.0），归属与致谢见仓库根目录 README。

const DEFAULT_TIME_SLOTS = [
  { number: 1, startTime: '07:50', endTime: '08:35' },
  { number: 2, startTime: '08:45', endTime: '09:30' },
  { number: 3, startTime: '09:50', endTime: '10:35' },
  { number: 4, startTime: '10:45', endTime: '11:30' },
  { number: 5, startTime: '11:31', endTime: '12:15' },
  { number: 6, startTime: '14:00', endTime: '14:45' },
  { number: 7, startTime: '14:55', endTime: '15:40' },
  { number: 8, startTime: '16:00', endTime: '16:45' },
  { number: 9, startTime: '16:55', endTime: '17:40' },
  { number: 10, startTime: '19:15', endTime: '20:00' },
  { number: 11, startTime: '20:10', endTime: '20:55' },
  { number: 12, startTime: '21:05', endTime: '21:50' }
]

function decodeEntities(value) {
  return String(value || '')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

function splitArgs(argsString) {
  const args = []
  let current = ''
  let depth = 0
  let quote = null
  let escaped = false
  for (const character of argsString) {
    if (quote) {
      current += character
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === quote) {
        quote = null
      }
    } else if (character === '"' || character === "'") {
      quote = character
      current += character
    } else if (character === '(' || character === '[' || character === '{') {
      depth += 1
      current += character
    } else if (character === ')' || character === ']' || character === '}') {
      depth -= 1
      current += character
    } else if (character === ',' && depth === 0) {
      args.push(current.trim())
      current = ''
    } else {
      current += character
    }
  }
  if (current.trim()) args.push(current.trim())
  return args
}

function stringLiteral(value) {
  if (!value || value.length < 2) return value || ''
  if (value[0] === '"' && value[value.length - 1] === '"') {
    try {
      return JSON.parse(value)
    } catch {
      return value.slice(1, -1)
    }
  }
  if (value[0] === "'" && value[value.length - 1] === "'") {
    return value.slice(1, -1).replace(/\\'/g, "'").replace(/\\\\/g, '\\')
  }
  return value
}

function resolveArg(argument, teacherNames, assistantName) {
  if (argument === undefined || argument === null || /^null$/i.test(argument)) return ''
  if (argument.includes('actTeacherName')) return teacherNames.join(',')
  if (argument.includes('actTeacherId')) return ''
  if (argument.includes('assistantName')) return assistantName
  return decodeEntities(stringLiteral(argument))
}

function resolveTaskActivityArgs(argsString, teacherNames, assistantName) {
  const args = splitArgs(argsString)
  if (args.length < 7) return null
  const rawName = resolveArg(args[3], teacherNames, assistantName)
  const courseName = rawName.replace(/\s*[（(][0-9A-Za-z.]{3,}[）)]\s*$/, '')
  const teacher = [resolveArg(args[1], teacherNames, assistantName), assistantName]
    .filter((value) => value && value.trim())
    .join(',')
  return {
    name: courseName,
    teacher,
    room: resolveArg(args[5], teacherNames, assistantName),
    validWeeks: resolveArg(args[6], teacherNames, assistantName)
  }
}

function parseTimetable(html) {
  if (typeof html !== 'string' || !html.trim()) throw new Error('课表源码为空')
  const unitCountMatch = html.match(/var\s+unitCount\s*=\s*(\d+)/)
  const marshalMatch = html.match(/marshalTable\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)/)
  if (!unitCountMatch || !marshalMatch) {
    throw new Error('没有找到 EAMS TaskActivity 课表数据，请确认文件来自登录后的学生课表页')
  }
  const unitCount = Number(unitCountMatch[1])
  const from = Number(marshalMatch[1])
  const startWeek = Number(marshalMatch[2])
  const endWeek = Number(marshalMatch[3])
  if (!unitCount || startWeek < 1 || endWeek < startWeek) throw new Error('课表周次数据异常')

  const segments = html.split('new TaskActivity(')
  const rawCourses = []
  for (let index = 1; index < segments.length; index += 1) {
    const segment = segments[index]
    const callEnd = segment.indexOf(');')
    if (callEnd < 0) continue
    const rest = segment.slice(callEnd + 2)
    const indexPattern = /index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;/g
    const indexMatches = [...rest.matchAll(indexPattern)]
    if (!indexMatches.length) continue

    const previous = segments[index - 1]
    const teacherDeclarations = [...previous.matchAll(/var\s+actTeachers\s*=\s*\[([^\]]*)\]/g)]
    const lastTeacherDeclaration = teacherDeclarations[teacherDeclarations.length - 1]
    const teacherNames = lastTeacherDeclaration
      ? [...lastTeacherDeclaration[1].matchAll(/name\s*:\s*"((?:\\.|[^"])*)"/g)]
          .map((match) => stringLiteral(`"${match[1]}"`))
      : []
    const assistantMatches = [...previous.matchAll(/var\s+assistantName\s*=\s*"((?:\\.|[^"])*)"/g)]
    const assistantName = assistantMatches.length
      ? stringLiteral(`"${assistantMatches[assistantMatches.length - 1][1]}"`)
      : ''
    const info = resolveTaskActivityArgs(segment.slice(0, callEnd), teacherNames, assistantName)
    if (!info || !/^[01]+$/.test(info.validWeeks)) continue

    const validStart = from + startWeek - 2
    let validWeeks = info.validWeeks
    if (validWeeks.slice(0, validStart).includes('1') && !validWeeks.slice(validStart).includes('1')) {
      validWeeks = `${validWeeks.slice(1)}0`
    }

    const weeks = []
    for (let week = startWeek; week <= endWeek; week += 1) {
      const validIndex = from + week - 2
      if (validIndex >= 0 && validIndex < validWeeks.length && validWeeks[validIndex] === '1') {
        weeks.push(week)
      }
    }
    if (!weeks.length) continue

    indexMatches.forEach((match) => {
      const day = Number(match[1]) + 1
      const section = Number(match[2]) + 1
      if (day >= 1 && day <= 7 && section >= 1 && section <= 12) {
        rawCourses.push({
          name: info.name,
          teacher: info.teacher,
          position: info.room,
          day,
          startSection: section,
          endSection: section,
          weeks: weeks.slice()
        })
      }
    })
  }
  const courses = mergeAndDistinctCourses(rawCourses)
  if (!courses.length) throw new Error('已识别课表结构，但没有解析到有效课程')
  return {
    format: 'zhusijiao-schedule',
    version: 1,
    school: '',
    name: '我的课表',
    semesterStart: '',
    totalWeeks: endWeek,
    timeSlots: DEFAULT_TIME_SLOTS.map((slot) => ({ ...slot })),
    courses
  }
}

function sameWeeks(first, second) {
  return first.join(',') === second.join(',')
}

function mergeAndDistinctCourses(courses) {
  if (!Array.isArray(courses) || !courses.length) return []
  const list = courses.map((course) => ({
    ...course,
    weeks: [...new Set((course.weeks || []).map(Number))].sort((a, b) => a - b)
  }))
  list.sort((a, b) => (
    a.name.localeCompare(b.name) ||
    a.teacher.localeCompare(b.teacher) ||
    a.position.localeCompare(b.position) ||
    a.day - b.day ||
    a.weeks.join(',').localeCompare(b.weeks.join(',')) ||
    a.startSection - b.startSection
  ))

  const consecutive = []
  let current = { ...list[0], weeks: [...list[0].weeks] }
  for (let index = 1; index < list.length; index += 1) {
    const next = list[index]
    const sameCourse = current.name === next.name && current.teacher === next.teacher &&
      current.position === next.position && current.day === next.day && sameWeeks(current.weeks, next.weeks)
    if (sameCourse && current.endSection + 1 === next.startSection) {
      current.endSection = next.endSection
    } else if (!(sameCourse && current.startSection === next.startSection && current.endSection === next.endSection)) {
      consecutive.push(current)
      current = { ...next, weeks: [...next.weeks] }
    }
  }
  consecutive.push(current)

  consecutive.sort((a, b) => (
    a.name.localeCompare(b.name) ||
    a.teacher.localeCompare(b.teacher) ||
    a.position.localeCompare(b.position) ||
    a.day - b.day ||
    a.startSection - b.startSection ||
    a.endSection - b.endSection
  ))
  const merged = []
  current = { ...consecutive[0], weeks: [...consecutive[0].weeks] }
  for (let index = 1; index < consecutive.length; index += 1) {
    const next = consecutive[index]
    const sameSlot = current.name === next.name && current.teacher === next.teacher &&
      current.position === next.position && current.day === next.day &&
      current.startSection === next.startSection && current.endSection === next.endSection
    if (sameSlot) {
      current.weeks = [...new Set([...current.weeks, ...next.weeks])].sort((a, b) => a - b)
    } else {
      merged.push(current)
      current = { ...next, weeks: [...next.weeks] }
    }
  }
  merged.push(current)
  return merged
}

function normalizeJsonPackage(value) {
  const source = value && value.schedule ? value.schedule : value
  if (!source || !Array.isArray(source.courses)) throw new Error('JSON 中没有 courses 数组')
  const courses = source.courses.map((course) => ({
    name: String(course.name || '').trim(),
    teacher: String(course.teacher || '').trim(),
    position: String(course.position || course.room || '').trim(),
    day: Number(course.day),
    startSection: Number(course.startSection),
    endSection: Number(course.endSection || course.startSection),
    weeks: Array.isArray(course.weeks) ? course.weeks.map(Number) : []
  })).filter((course) => (
    course.name && course.day >= 1 && course.day <= 7 &&
    course.startSection >= 1 && course.endSection <= 12 && course.weeks.length
  ))
  if (!courses.length) throw new Error('JSON 中没有有效课程')
  const maxWeek = Math.max(...courses.flatMap((course) => course.weeks))
  return {
    format: 'zhusijiao-schedule',
    version: 1,
    school: '',
    name: String(source.name || '我的课表').trim(),
    semesterStart: String(source.semesterStart || '').trim(),
    totalWeeks: Number(source.totalWeeks || maxWeek || 20),
    timeSlots: Array.isArray(source.timeSlots) && source.timeSlots.length
      ? source.timeSlots.slice(0, 12)
      : DEFAULT_TIME_SLOTS.map((slot) => ({ ...slot })),
    courses: mergeAndDistinctCourses(courses)
  }
}

function parseImportContent(content) {
  let source = String(content || '').trim()
  if (!source) throw new Error('导入内容为空')
  if (source.startsWith('```')) {
    source = source.replace(/^```(?:json|html|javascript)?\s*/i, '').replace(/\s*```$/, '')
  }
  if (source[0] === '{' || source[0] === '[') {
    try {
      return normalizeJsonPackage(JSON.parse(source))
    } catch (error) {
      if (error.message !== 'JSON 中没有 courses 数组' && error.message !== 'JSON 中没有有效课程') {
        throw new Error(`JSON 格式错误：${error.message}`)
      }
      throw error
    }
  }
  return parseTimetable(source)
}

module.exports = {
  DEFAULT_TIME_SLOTS,
  mergeAndDistinctCourses,
  parseImportContent,
  parseTimetable,
  splitArgs
}
