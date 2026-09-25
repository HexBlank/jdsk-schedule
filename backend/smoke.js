// 后端轻量冒烟测试：用 Fastify inject 在进程内跑通关键链路（无需真实端口）。
// 运行：node smoke.js
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { readConfig } = require('./src/config')
const { openDatabase } = require('./src/db')
const { buildApp } = require('./src/app')

function check(name, cond, extra) {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`)
  if (!cond) process.exitCode = 1
}

;(async () => {
  // 应用更新分发：临时目录里放 release.json + 假 APK，验证版本信息与下载链路
  const appFilesDir = fs.mkdtempSync(path.join(os.tmpdir(), 'zhusijiao-app-'))
  fs.writeFileSync(path.join(appFilesDir, 'release.json'), JSON.stringify({
    versionCode: 2, versionName: '1.1.0', changelog: '测试更新', fileName: 'app-1.1.0.apk'
  }))
  fs.writeFileSync(path.join(appFilesDir, 'app-1.1.0.apk'), 'apk-bytes')

  const config = readConfig({
    NODE_ENV: 'development', DATABASE_PATH: ':memory:', ENABLE_DEVICE_AUTH: 'true', APP_FILES_DIR: appFilesDir
  })
  const db = openDatabase(':memory:')
  const app = buildApp({ config, db, logger: false })
  await app.ready()

  const health = await app.inject({ method: 'GET', url: '/healthz' })
  check('healthz', health.statusCode === 200 && health.json().ok === true)

  const release = await app.inject({ method: 'GET', url: '/app/release.json' })
  check('版本信息 release.json', release.statusCode === 200 && release.json().versionName === '1.1.0')

  const apk = await app.inject({ method: 'GET', url: '/app/download/app-1.1.0.apk' })
  check('下载 APK', apk.statusCode === 200 && apk.body === 'apk-bytes' &&
    apk.headers['content-type'] === 'application/vnd.android.package-archive')

  const missingApk = await app.inject({ method: 'GET', url: '/app/download/none.apk' })
  check('下载不存在的 APK 返回 404', missingApk.statusCode === 404)

  const traversal = await app.inject({ method: 'GET', url: '/app/download/..%2Fdb.js' })
  check('APK 路径穿越被拒绝', traversal.statusCode === 400)

  // 多更新通道：/app/ch/<name>/release.json 与同目录下载
  fs.mkdirSync(path.join(appFilesDir, 'ch', 'beta'), { recursive: true })
  fs.writeFileSync(path.join(appFilesDir, 'ch', 'beta', 'release.json'),
    JSON.stringify({ versionCode: 3, versionName: '9.9.0-beta', changelog: 'beta', fileName: 'beta-1.apk' }))
  fs.writeFileSync(path.join(appFilesDir, 'ch', 'beta', 'beta-1.apk'), 'beta-apk-bytes')
  const betaRelease = await app.inject({ method: 'GET', url: '/app/ch/beta/release.json' })
  check('通道版本信息 /app/ch/beta/release.json',
    betaRelease.statusCode === 200 && betaRelease.json().versionName === '9.9.0-beta')
  const betaApk = await app.inject({ method: 'GET', url: '/app/ch/beta/download/beta-1.apk' })
  check('通道 APK 下载', betaApk.statusCode === 200 && betaApk.body === 'beta-apk-bytes')
  const badChannel = await app.inject({ method: 'GET', url: '/app/ch/..%2Fevil/release.json' })
  check('非法通道名被拒绝', badChannel.statusCode === 400)
  const missingChannel = await app.inject({ method: 'GET', url: '/app/ch/nightly/release.json' })
  check('未发布的通道返回 404', missingChannel.statusCode === 404)

  const auth = await app.inject({ method: 'POST', url: '/api/v1/auth/device', payload: { deviceId: 'test-device-0001' } })
  const token = auth.json().token
  check('auth/device 签发 JWT', auth.statusCode === 200 && !!token)

  const bad = await app.inject({ method: 'POST', url: '/api/v1/auth/device', payload: { deviceId: 'x' } })
  check('非法 deviceId 被拒绝', bad.statusCode === 400, `code=${bad.json().error && bad.json().error.code}`)

  const list0 = await app.inject({ method: 'GET', url: '/api/v1/schedules', headers: { Authorization: `Bearer ${token}` } })
  check('初始课表列表为空', list0.statusCode === 200 && list0.json().schedules.length === 0)

  const payload = {
    name: '测试课表', semesterStart: '2026-09-07', totalWeeks: 20, timeSlots: [],
    courses: [{ name: '数据结构', teacher: '陈老师', position: 'A201', day: 1, startSection: 1, endSection: 2, weeks: [1, 2, 3] }]
  }
  const created = await app.inject({ method: 'POST', url: '/api/v1/schedules', headers: { Authorization: `Bearer ${token}` }, payload })
  const code = created.json().schedule && created.json().schedule.shareCode
  check('发布课表并生成分享码', created.statusCode === 201 && !!code, `shareCode=${code}`)

  const auth2 = await app.inject({ method: 'POST', url: '/api/v1/auth/device', payload: { deviceId: 'test-device-0002' } })
  const token2 = auth2.json().token

  const preview = await app.inject({ method: 'POST', url: '/api/v1/share/preview', headers: { Authorization: `Bearer ${token2}` }, payload: { code } })
  check('分享码预览', preview.statusCode === 200 && preview.json().schedule.name === '测试课表')

  const join = await app.inject({ method: 'POST', url: '/api/v1/share/join', headers: { Authorization: `Bearer ${token2}` }, payload: { code } })
  check('加入课表成为订阅者', join.statusCode === 200 && join.json().schedule.role === 'subscriber')

  const detail = await app.inject({ method: 'GET', url: `/api/v1/schedules/${join.json().schedule.id}`, headers: { Authorization: `Bearer ${token2}` } })
  check('订阅者读取详情不含分享码', detail.statusCode === 200 && detail.json().schedule.shareCode === undefined)

  const scheduleId = created.json().schedule.id
  const courseId = created.json().schedule.courses[0].id
  const adjustmentPayload = {
    courseId,
    sourceWeek: 1,
    sourceDay: 1,
    sourceStartSection: 1,
    sourceEndSection: 2,
    targetWeek: 2,
    targetDay: 3,
    targetStartSection: 3,
    targetEndSection: 4,
    targetPosition: 'B302'
  }
  const adjusted = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/adjustments`,
    headers: { Authorization: `Bearer ${token}` }, payload: { ...adjustmentPayload, _expectedRevision: 1 }
  })
  const adjustment = adjusted.json().schedule && adjusted.json().schedule.adjustments[0]
  check('发布者创建单周调课', adjusted.statusCode === 201 && adjustment && adjustment.targetDay === 3)

  const forbiddenAdjustment = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/adjustments`,
    headers: { Authorization: `Bearer ${token2}` }, payload: { ...adjustmentPayload, sourceWeek: 2, _expectedRevision: 2 }
  })
  check('订阅者不能调课', forbiddenAdjustment.statusCode === 404)

  const forbiddenSave = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}`,
    headers: { Authorization: `Bearer ${token2}` }, payload
  })
  check('订阅者不能覆盖发布者的课表', forbiddenSave.statusCode === 404)

  const forbiddenRemove = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${scheduleId}`,
    headers: { Authorization: `Bearer ${token2}` }
  })
  check('订阅者不能删除发布者的课表', forbiddenRemove.statusCode === 404)

  const updatedAdjustment = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}/adjustments/${adjustment.id}`,
    headers: { Authorization: `Bearer ${token}` }, payload: {
      ...adjustmentPayload, targetStartSection: 5, targetEndSection: 6, _expectedRevision: 2
    }
  })
  check('发布者修改调课', updatedAdjustment.statusCode === 200 && updatedAdjustment.json().schedule.adjustments[0].targetStartSection === 5)

  const removedAdjustment = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${scheduleId}/adjustments/${adjustment.id}?revision=3`,
    headers: { Authorization: `Bearer ${token}` }
  })
  check('发布者撤销调课', removedAdjustment.statusCode === 200 && removedAdjustment.json().schedule.adjustments.length === 0)

  // ===== 停课 / 补课（调休）=====
  const holidayCreated = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/holidays`,
    headers: { Authorization: `Bearer ${token}` }, payload: { week: 5, day: 2, _expectedRevision: 4 }
  })
  const holiday = holidayCreated.json().schedule && holidayCreated.json().schedule.holidays[0]
  check('发布者设置停课日', holidayCreated.statusCode === 201 && holiday && holiday.week === 5 && holiday.day === 2)

  const duplicateHoliday = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/holidays`,
    headers: { Authorization: `Bearer ${token}` }, payload: { week: 5, day: 2, _expectedRevision: 5 }
  })
  check('重复停课日被拒绝', duplicateHoliday.statusCode === 409)

  const makeupCreated = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/makeups`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { sourceWeek: 5, sourceDay: 2, targetWeek: 2, targetDay: 6, _expectedRevision: 5 }
  })
  const makeup = makeupCreated.json().schedule && makeupCreated.json().schedule.makeups[0]
  check('发布者设置补课日', makeupCreated.statusCode === 201 && makeup && makeup.targetWeek === 2)

  const sameSourceMakeup = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/makeups`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { sourceWeek: 5, sourceDay: 2, targetWeek: 2, targetDay: 7, _expectedRevision: 6 }
  })
  check('同一来源日不能重复补课', sameSourceMakeup.statusCode === 409)

  const makeupOnHoliday = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/makeups`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { sourceWeek: 5, sourceDay: 3, targetWeek: 5, targetDay: 2, _expectedRevision: 6 }
  })
  check('补课不能落在停课日', makeupOnHoliday.statusCode === 409)

  const subscriberHoliday = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/holidays`,
    headers: { Authorization: `Bearer ${token2}` }, payload: { week: 4, day: 1, _expectedRevision: 6 }
  })
  check('订阅者不能设置停课', subscriberHoliday.statusCode === 404)

  const sameDayMakeup = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/makeups`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { sourceWeek: 5, sourceDay: 2, targetWeek: 5, targetDay: 2, _expectedRevision: 6 }
  })
  check('补课日不能与来源同一天', sameDayMakeup.statusCode === 400)

  const removedHoliday = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${scheduleId}/holidays/${holiday.id}?revision=6`,
    headers: { Authorization: `Bearer ${token}` }
  })
  check('发布者撤销停课', removedHoliday.statusCode === 200 &&
    removedHoliday.json().schedule.holidays.length === 0)

  const removedMakeup = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${scheduleId}/makeups/${makeup.id}?revision=7`,
    headers: { Authorization: `Bearer ${token}` }
  })
  check('发布者撤销补课', removedMakeup.statusCode === 200 && removedMakeup.json().schedule.makeups.length === 0)

  const holidayBeforePut = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/holidays`,
    headers: { Authorization: `Bearer ${token}` }, payload: { week: 3, day: 1, _expectedRevision: 8 }
  })
  check('停课日可再次添加', holidayBeforePut.statusCode === 201)

  const adjustedAgain = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/adjustments`,
    headers: { Authorization: `Bearer ${token}` }, payload: { ...adjustmentPayload, _expectedRevision: 9 }
  })
  const createdFromImport = await app.inject({
    method: 'POST', url: '/api/v1/schedules',
    headers: { Authorization: `Bearer ${token}` },
    payload: { ...payload, name: '教务导入的新课表' }
  })
  const originalAfterCreate = await app.inject({
    method: 'GET', url: `/api/v1/schedules/${scheduleId}`,
    headers: { Authorization: `Bearer ${token}` }
  })
  check('教务新建课表不会改动原课表', adjustedAgain.statusCode === 201 &&
    createdFromImport.statusCode === 201 &&
    createdFromImport.json().schedule.id !== scheduleId &&
    originalAfterCreate.json().schedule.adjustments.length === 1)

  // ===== 课程颜色（长按手动选色） =====
  const courseColorsSet = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}/course-colors`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { colors: { '数据结构': '#B54E6C' }, _expectedRevision: 10 }
  })
  check('发布者设置课程颜色', courseColorsSet.statusCode === 200 &&
    courseColorsSet.json().schedule.courseColors['数据结构'] === '#B54E6C')

  const badColor = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}/course-colors`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { colors: { '数据结构': 'red' }, _expectedRevision: 11 }
  })
  check('非法颜色值被拒绝', badColor.statusCode === 400)

  const subscriberColors = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}/course-colors`,
    headers: { Authorization: `Bearer ${token2}` },
    payload: { colors: {}, _expectedRevision: 11 }
  })
  check('订阅者不能改课程颜色', subscriberColors.statusCode === 404)

  const reimported = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}`,
    headers: { Authorization: `Bearer ${token}` },
    payload: {
      ...payload,
      courses: [{ ...payload.courses[0], name: '重命名后的数据结构' }],
      _expectedRevision: 11
    }
  })
  check('教务整表覆盖会清空旧调课', reimported.statusCode === 200 &&
    reimported.json().schedule.adjustments.length === 0 &&
    reimported.json().schedule.holidays.length === 0 &&
    reimported.json().schedule.makeups.length === 0 &&
    reimported.json().schedule.courses[0].name === '重命名后的数据结构' &&
    reimported.json().schedule.courseColors['数据结构'] === '#B54E6C')

  const replayedAdjustment = await app.inject({
    method: 'POST', url: `/api/v1/schedules/${scheduleId}/adjustments`,
    headers: { Authorization: `Bearer ${token}` },
    payload: {
      ...adjustmentPayload,
      courseId: reimported.json().schedule.courses[0].id,
      _expectedRevision: 12
    }
  })
  check('离线快照同步后可重放调课', replayedAdjustment.statusCode === 201 &&
    replayedAdjustment.json().schedule.adjustments.length === 1 &&
    replayedAdjustment.json().schedule.adjustments[0].courseSnapshot.name === '重命名后的数据结构')

  const colorsCleared = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}/course-colors`,
    headers: { Authorization: `Bearer ${token}` },
    payload: { colors: {}, _expectedRevision: 13 }
  })
  check('课程颜色可清空', colorsCleared.statusCode === 200 &&
    Object.keys(colorsCleared.json().schedule.courseColors).length === 0)

  const staleUpdate = await app.inject({
    method: 'PUT', url: `/api/v1/schedules/${scheduleId}`,
    headers: { Authorization: `Bearer ${token}` }, payload: { ...payload, _expectedRevision: 1 }
  })
  check('过期 revision 不能覆盖新课表', staleUpdate.statusCode === 409)

  const parse = await app.inject({
    method: 'POST', url: '/api/v1/imports/parse', headers: { Authorization: `Bearer ${token}` },
    payload: { content: JSON.stringify({ format: 'zhusijiao-schedule', courses: [{ name: 'X', day: 1, startSection: 1, endSection: 1, weeks: [1] }] }) }
  })
  check('服务端解析导入内容', parse.statusCode === 200 && Array.isArray(parse.json().schedule.courses))

  const ghostId = '00000000-0000-4000-8000-000000000000'
  const statusOf = async (id, tk) => (await app.inject({
    method: 'GET', url: `/api/v1/schedules/${id}/status`, headers: { Authorization: `Bearer ${tk}` }
  })).json().status
  const ownerStatus = await statusOf(scheduleId, token)
  check('状态接口：发布者', ownerStatus.exists && ownerStatus.owned && ownerStatus.member)
  const memberStatus = await statusOf(scheduleId, token2)
  check('状态接口：订阅者', memberStatus.exists && !memberStatus.owned && memberStatus.member)
  const ghostStatus = await statusOf(ghostId, token2)
  check('状态接口：课表已删除', ghostStatus.exists === false && ghostStatus.member === false)
  await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${scheduleId}/membership`, headers: { Authorization: `Bearer ${token2}` }
  })
  const leftStatus = await statusOf(scheduleId, token2)
  check('状态接口：已退出但课表仍在', leftStatus.exists === true && leftStatus.member === false)
  const leaveGhost = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${ghostId}/membership`, headers: { Authorization: `Bearer ${token2}` }
  })
  check('退出已不存在的课表按成功处理（幂等）', leaveGhost.statusCode === 204)
  const removeGhost = await app.inject({
    method: 'DELETE', url: `/api/v1/schedules/${ghostId}`, headers: { Authorization: `Bearer ${token}` }
  })
  check('删除已不存在的课表按成功处理（幂等）', removeGhost.statusCode === 204)

  const me = await app.inject({ method: 'DELETE', url: '/api/v1/me', headers: { Authorization: `Bearer ${token2}` } })
  check('删除本人数据', me.statusCode === 204)

  // ===== 情侣课表 =====
  const login = async (deviceId) => (await app.inject({
    method: 'POST', url: '/api/v1/auth/device', payload: { deviceId }
  })).json().token
  const call = (tk, method, url, body) => app.inject({
    method, url, headers: { Authorization: `Bearer ${tk}` }, ...(body === undefined ? {} : { payload: body })
  })
  const coupleOf = async (tk) => (await call(tk, 'GET', '/api/v1/couple')).json().couple
  const tA = await login('couple-device-a01')
  const tB = await login('couple-device-b01')
  const tC = await login('couple-device-c01')

  const unbound = await coupleOf(tA)
  check('情侣：初始未绑定', unbound.bound === false && unbound.invite === null)

  const invite1 = await call(tA, 'POST', '/api/v1/couple/invites')
  const code1 = invite1.json().invite && invite1.json().invite.code
  check('情侣：生成 8 位邀请码', invite1.statusCode === 201 && /^[2-9A-HJ-NP-Z]{8}$/.test(code1))
  check('情侣：未绑定时能查到自己的有效邀请码', (await coupleOf(tA)).invite.code === code1)

  const invite2 = await call(tA, 'POST', '/api/v1/couple/invites')
  const code2 = invite2.json().invite.code
  const oldCode = await call(tB, 'POST', '/api/v1/couple/accept', { code: code1 })
  check('情侣：新邀请码替换旧码', code2 !== code1 && oldCode.statusCode === 404)

  const selfAccept = await call(tA, 'POST', '/api/v1/couple/accept', { code: code2 })
  check('情侣：不能接受自己的邀请码', selfAccept.statusCode === 400)
  const badFormat = await call(tB, 'POST', '/api/v1/couple/accept', { code: 'abc' })
  check('情侣：邀请码格式错误被拒绝', badFormat.statusCode === 400)

  const accepted = await call(tB, 'POST', '/api/v1/couple/accept', { code: code2.toLowerCase() })
  const bView = accepted.json().couple
  check('情侣：接受邀请后绑定成功（邀请码不区分大小写）', accepted.statusCode === 200 && bView.bound === true)
  check('情侣：默认颜色发邀请的人蓝、接受的人粉',
    bView.me.color === '#F4AFC2' && bView.partner.color === '#7FAEE3')
  check('情侣：刚绑定时对方还没有课表', bView.partner.schedule === null)

  const reused = await call(tC, 'POST', '/api/v1/couple/accept', { code: code2 })
  check('情侣：邀请码用一次就作废', reused.statusCode === 404)
  const inviteC = await call(tC, 'POST', '/api/v1/couple/invites')
  const acceptWhileBound = await call(tA, 'POST', '/api/v1/couple/accept', { code: inviteC.json().invite.code })
  check('情侣：已绑定的人不能再接受邀请', acceptWhileBound.statusCode === 409)
  const inviteWhileBound = await call(tA, 'POST', '/api/v1/couple/invites')
  check('情侣：已绑定的人不能再发邀请', inviteWhileBound.statusCode === 409)

  // B 自己没导入，用的是舍友 C 分享的课表
  const cSchedule = (await call(tC, 'POST', '/api/v1/schedules', { ...payload, name: '张三的课表' })).json().schedule
  await call(tB, 'POST', '/api/v1/share/join', { code: cSchedule.shareCode })
  const reportB = await call(tB, 'PUT', '/api/v1/couple/current-schedule', { scheduleId: cSchedule.id })
  check('情侣：可以上报加入的舍友课表为当前课表', reportB.statusCode === 200 && reportB.json().couple.me.currentScheduleId === cSchedule.id)
  const aSeesB = await coupleOf(tA)
  check('情侣：对方摘要只有课表 id 和版本号', aSeesB.partner.schedule && aSeesB.partner.schedule.id === cSchedule.id &&
    aSeesB.partner.schedule.name === undefined)
  const partnerDetail = await call(tA, 'GET', '/api/v1/couple/partner-schedule')
  const pd = partnerDetail.json().schedule
  check('情侣：读取对方课表不含分享码和课表原名', partnerDetail.statusCode === 200 && pd.role === 'partner' &&
    pd.shareCode === undefined && pd.name === '' && pd.courses.length === 1)

  const reportForeign = await call(tA, 'PUT', '/api/v1/couple/current-schedule', { scheduleId: cSchedule.id })
  check('情侣：不能上报自己没加入的课表', reportForeign.statusCode === 404)
  const aSchedule = (await call(tA, 'POST', '/api/v1/schedules', payload)).json().schedule
  await call(tA, 'PUT', '/api/v1/couple/current-schedule', { scheduleId: aSchedule.id })
  const bSeesA = await coupleOf(tB)
  check('情侣：上报自己发布的课表', bSeesA.partner.schedule && bSeesA.partner.schedule.id === aSchedule.id &&
    bSeesA.partner.sameScheduleAsMine === false)

  await call(tB, 'DELETE', `/api/v1/schedules/${cSchedule.id}/membership`)
  const afterLeave = await coupleOf(tA)
  const detailAfterLeave = await call(tA, 'GET', '/api/v1/couple/partner-schedule')
  check('情侣：对方退出舍友课表后立刻读不到', afterLeave.partner.schedule === null && detailAfterLeave.statusCode === 404)

  const renamed = await call(tB, 'PUT', '/api/v1/couple/members/partner', { nickname: '  大熊  ' })
  check('情侣：可以给对方改名', renamed.statusCode === 200 && renamed.json().couple.partner.nickname === '大熊' &&
    renamed.json().couple.partner.nicknameUpdatedBy === 'me')
  const aAfterRename = await coupleOf(tA)
  check('情侣：被改名的一方看到是对方改的', aAfterRename.me.nickname === '大熊' && aAfterRename.me.nicknameUpdatedBy === 'partner')
  const recolor = await call(tA, 'PUT', '/api/v1/couple/members/me', { color: '#8fd1bf', nickname: '大熊' })
  const recolored = recolor.json().couple.me
  check('情侣：改颜色，值没变的名字不改最后修改人', recolored.color === '#8FD1BF' && recolored.colorUpdatedBy === 'me' &&
    recolored.nicknameUpdatedBy === 'partner')
  const emojiName = await call(tA, 'PUT', '/api/v1/couple/members/partner', { nickname: '小鹿🦌小鹿🦌小鹿' })
  check('情侣：名字按字数计长度（emoji 算一个字）', emojiName.statusCode === 200)
  const tooLong = await call(tA, 'PUT', '/api/v1/couple/members/partner', { nickname: '一二三四五六七八九' })
  const badColorValue = await call(tA, 'PUT', '/api/v1/couple/members/partner', { color: 'pink' })
  const badWho = await call(tA, 'PUT', '/api/v1/couple/members/someone', { color: '#FFFFFF' })
  const emptyProfile = await call(tA, 'PUT', '/api/v1/couple/members/me', {})
  check('情侣：名字超长、颜色非法、对象非法、空修改都被拒绝',
    tooLong.statusCode === 400 && badColorValue.statusCode === 400 && badWho.statusCode === 400 && emptyProfile.statusCode === 400)

  await call(tA, 'DELETE', `/api/v1/schedules/${aSchedule.id}`)
  check('情侣：对方删除当前课表后摘要为空', (await coupleOf(tB)).partner.schedule === null)

  const unbindB = await call(tB, 'DELETE', '/api/v1/couple')
  const aAfterUnbind = await coupleOf(tA)
  const partnerAfterUnbind = await call(tA, 'GET', '/api/v1/couple/partner-schedule')
  const unbindAgain = await call(tB, 'DELETE', '/api/v1/couple')
  check('情侣：一方解绑，双方立刻解除', unbindB.statusCode === 204 && aAfterUnbind.bound === false &&
    partnerAfterUnbind.statusCode === 404)
  check('情侣：重复解绑按成功处理（幂等）', unbindAgain.statusCode === 204)
  const renameAfterUnbind = await call(tA, 'PUT', '/api/v1/couple/members/me', { nickname: 'x' })
  check('情侣：未绑定时不能改名字', renameAfterUnbind.statusCode === 404)

  const rebindCode = (await call(tA, 'POST', '/api/v1/couple/invites')).json().invite.code
  const rebind = await call(tB, 'POST', '/api/v1/couple/accept', { code: rebindCode })
  check('情侣：解绑后可重新绑定，名字颜色从默认开始', rebind.statusCode === 200 &&
    rebind.json().couple.partner.nickname === '' && rebind.json().couple.partner.color === '#7FAEE3')
  await call(tB, 'DELETE', '/api/v1/me')
  check('情侣：对方注销后自动变为未绑定', (await coupleOf(tA)).bound === false)

  fs.rmSync(appFilesDir, { recursive: true, force: true })
  await app.close()
  console.log(process.exitCode ? '\n存在失败项' : '\n全部通过')
})().catch((e) => { console.error('SMOKE FAIL', e); process.exit(1) })
