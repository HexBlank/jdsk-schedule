// ==UserScript==
// @name         几点上课 · 课表导出助手
// @namespace    https://zhusijiao.local/
// @version      0.1.0
// @description  在浏览器本地解析学校 EAMS 课表并导出几点上课 JSON，不上传账号、密码或 Cookie。
// @match        https://webvpn.hstc.edu.cn/*
// @match        http://jw.hstc.edu.cn/*
// @match        https://jw.hstc.edu.cn/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function () {
  'use strict'

  const TIME_SLOTS = [
    ['07:50', '08:35'], ['08:45', '09:30'], ['09:50', '10:35'], ['10:45', '11:30'],
    ['11:31', '12:15'], ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'],
    ['16:55', '17:40'], ['19:15', '20:00'], ['20:10', '20:55'], ['21:05', '21:50']
  ].map((time, index) => ({ number: index + 1, startTime: time[0], endTime: time[1] }))

  function baseUrl() {
    const url = new URL(window.location.href)
    const index = url.pathname.indexOf('/eams/')
    return index >= 0 ? url.origin + url.pathname.slice(0, index) : url.origin
  }

  function eamsPath(path) {
    return `${baseUrl()}/eams${path}`
  }

  function splitArgs(source) {
    const args = []
    let current = ''
    let depth = 0
    let quote = null
    let escaped = false
    for (const char of source) {
      if (quote) {
        current += char
        if (escaped) escaped = false
        else if (char === '\\') escaped = true
        else if (char === quote) quote = null
      } else if (char === '"' || char === "'") {
        quote = char
        current += char
      } else if ('([{'.includes(char)) {
        depth += 1
        current += char
      } else if (')]}'.includes(char)) {
        depth -= 1
        current += char
      } else if (char === ',' && depth === 0) {
        args.push(current.trim())
        current = ''
      } else current += char
    }
    if (current.trim()) args.push(current.trim())
    return args
  }

  function literal(value) {
    if (!value || value.length < 2) return value || ''
    if (value.startsWith('"') && value.endsWith('"')) {
      try { return JSON.parse(value) } catch { return value.slice(1, -1) }
    }
    if (value.startsWith("'") && value.endsWith("'")) return value.slice(1, -1)
    return value
  }

  function arg(value, teachers, assistant) {
    if (!value || value === 'null') return ''
    if (value.includes('actTeacherName')) return teachers.join(',')
    if (value.includes('actTeacherId')) return ''
    if (value.includes('assistantName')) return assistant
    return literal(value)
      .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
  }

  function merge(courses) {
    const list = courses.map((course) => ({ ...course, weeks: [...new Set(course.weeks)].sort((a, b) => a - b) }))
    list.sort((a, b) => a.name.localeCompare(b.name) || a.teacher.localeCompare(b.teacher) ||
      a.position.localeCompare(b.position) || a.day - b.day || a.weeks.join(',').localeCompare(b.weeks.join(',')) ||
      a.startSection - b.startSection)
    const firstPass = []
    let current = list[0]
    for (let index = 1; index < list.length; index += 1) {
      const next = list[index]
      const same = current.name === next.name && current.teacher === next.teacher &&
        current.position === next.position && current.day === next.day && current.weeks.join(',') === next.weeks.join(',')
      if (same && current.endSection + 1 === next.startSection) current.endSection = next.endSection
      else if (!(same && current.startSection === next.startSection && current.endSection === next.endSection)) {
        firstPass.push(current)
        current = next
      }
    }
    firstPass.push(current)
    firstPass.sort((a, b) => a.name.localeCompare(b.name) || a.teacher.localeCompare(b.teacher) ||
      a.position.localeCompare(b.position) || a.day - b.day || a.startSection - b.startSection || a.endSection - b.endSection)
    const result = []
    current = firstPass[0]
    for (let index = 1; index < firstPass.length; index += 1) {
      const next = firstPass[index]
      const same = current.name === next.name && current.teacher === next.teacher &&
        current.position === next.position && current.day === next.day &&
        current.startSection === next.startSection && current.endSection === next.endSection
      if (same) current.weeks = [...new Set([...current.weeks, ...next.weeks])].sort((a, b) => a - b)
      else { result.push(current); current = next }
    }
    result.push(current)
    return result
  }

  function parse(html) {
    const unit = html.match(/var\s+unitCount\s*=\s*(\d+)/)
    const marshal = html.match(/marshalTable\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)/)
    if (!unit || !marshal) throw new Error('页面中没有课表数据，请先从 WebVPN 打开教务系统的“学生课表”页面')
    const unitCount = Number(unit[1])
    const from = Number(marshal[1])
    const startWeek = Number(marshal[2])
    const endWeek = Number(marshal[3])
    const segments = html.split('new TaskActivity(')
    const raw = []
    for (let index = 1; index < segments.length; index += 1) {
      const segment = segments[index]
      const callEnd = segment.indexOf(');')
      if (callEnd < 0) continue
      const occupied = [...segment.slice(callEnd + 2).matchAll(/index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;/g)]
      if (!occupied.length) continue
      const previous = segments[index - 1]
      const declarations = [...previous.matchAll(/var\s+actTeachers\s*=\s*\[([^\]]*)\]/g)]
      const declaration = declarations[declarations.length - 1]
      const teachers = declaration ? [...declaration[1].matchAll(/name\s*:\s*"((?:\\.|[^"])*)"/g)].map((m) => literal(`"${m[1]}"`)) : []
      const assistants = [...previous.matchAll(/var\s+assistantName\s*=\s*"((?:\\.|[^"])*)"/g)]
      const assistant = assistants.length ? literal(`"${assistants[assistants.length - 1][1]}"`) : ''
      const args = splitArgs(segment.slice(0, callEnd))
      if (args.length < 7) continue
      let valid = arg(args[6], teachers, assistant)
      if (!/^[01]+$/.test(valid)) continue
      const validStart = from + startWeek - 2
      if (valid.slice(0, validStart).includes('1') && !valid.slice(validStart).includes('1')) valid = `${valid.slice(1)}0`
      const weeks = []
      for (let week = startWeek; week <= endWeek; week += 1) {
        if (valid[from + week - 2] === '1') weeks.push(week)
      }
      if (!weeks.length) continue
      const name = arg(args[3], teachers, assistant).replace(/\s*[（(][0-9A-Za-z.]{3,}[）)]\s*$/, '')
      occupied.forEach((match) => {
        const day = Number(match[1]) + 1
        const section = Number(match[2]) + 1
        if (day < 1 || day > 7 || section < 1 || section > unitCount) return
        raw.push({
          name,
          teacher: [arg(args[1], teachers, assistant), assistant].filter(Boolean).join(','),
          position: arg(args[5], teachers, assistant),
          day,
          startSection: section,
          endSection: section,
          weeks: weeks.slice()
        })
      })
    }
    if (!raw.length) throw new Error('没有解析到有效课程')
    return { courses: merge(raw), totalWeeks: endWeek }
  }

  // 学期 id 提取（按可靠性排序，全部失败返回 null）：
  // ① 页面学期下拉框中被选中的项 ② 隐藏域 ③ 当前页 URL 参数
  // ④ 校历控件 semesterCalendar({...value}) ⑤ Cookie
  function semesterFrom(html) {
    const select = html.match(/<select[^>]*name=["']semester\.id["'][^>]*>([\s\S]*?)<\/select>/i)
    if (select) {
      for (const tag of select[1].match(/<option\b[^>]*>/gi) || []) {
        const value = (tag.match(/value=["'](\d+)["']/i) || [])[1]
        if (value && /\bselected\b/i.test(tag)) return value
      }
    }
    const hidden = html.match(/<input[^>]*name=["']semester\.id["'][^>]*value=["'](\d+)["']/i)
      || html.match(/<input[^>]*value=["'](\d+)["'][^>]*name=["']semester\.id["']/i)
    if (hidden) return hidden[1]
    const fromUrl = (window.location.search || '').match(/[?&]semester\.id=(\d+)/)
    if (fromUrl) return fromUrl[1]
    const calendar = html.match(/semesterCalendar\(\{[^}]*?value:\s*"(\d+)"/)
    if (calendar) return calendar[1]
    const cookie = (document.cookie || '').match(/semester\.id=(\d+)/)
    if (cookie) return cookie[1]
    return null
  }

  async function studentInfo() {
    let html
    if (/courseTableForStd\.action/.test(location.href)) html = document.documentElement.outerHTML
    else {
      const response = await fetch(eamsPath('/courseTableForStd.action'), { credentials: 'include' })
      html = await response.text()
    }
    const ids = [...html.matchAll(/addInput\(form,"ids","(\d+)"\)/g)].map((m) => m[1])
    const semesterId = semesterFrom(html)
    if (!ids.length || !semesterId) throw new Error('未获取到学生或学期信息，请确认已经登录教务系统')
    return { id: ids[0], semesterId }
  }

  async function fetchCourseTable(info) {
    const response = await fetch(eamsPath('/courseTableForStd!courseTable.action'), {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
      body: `ignoreHead=1&setting.kind=std&startWeek=&semester.id=${encodeURIComponent(info.semesterId)}&ids=${encodeURIComponent(info.id)}`
    })
    if (!response.ok) throw new Error(`课表请求失败（${response.status}）`)
    return response.text()
  }

  function download(data) {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `几点上课课表-${new Date().toISOString().slice(0, 10)}.json`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  async function run(button) {
    button.disabled = true
    button.textContent = '正在读取课表…'
    try {
      const info = await studentInfo()
      const parsed = parse(await fetchCourseTable(info))
      const semesterStart = prompt('请输入第一周周一的日期（YYYY-MM-DD）', '') || ''
      const name = prompt('给这份课表起个名字', '我的课表') || '我的课表'
      download({
        format: 'zhusijiao-schedule', version: 1, school: '',
        name, semesterStart, totalWeeks: parsed.totalWeeks, timeSlots: TIME_SLOTS, courses: parsed.courses
      })
      button.textContent = `已导出 ${parsed.courses.length} 个时段`
    } catch (error) {
      alert(`几点上课导出失败：${error.message}`)
      button.textContent = '导出到几点上课'
    } finally {
      button.disabled = false
    }
  }

  const button = document.createElement('button')
  button.textContent = '导出到几点上课'
  button.title = '只在当前浏览器读取并解析课表，不上传账号、密码或 Cookie'
  Object.assign(button.style, {
    position: 'fixed', zIndex: '2147483647', right: '20px', bottom: '22px', padding: '13px 20px',
    border: '0', borderRadius: '16px', color: '#fffdf7', background: '#17322d', boxShadow: '0 10px 30px rgba(0,0,0,.24)',
    fontSize: '15px', fontWeight: '700', cursor: 'pointer'
  })
  button.addEventListener('click', () => run(button))
  document.body.appendChild(button)
})()
