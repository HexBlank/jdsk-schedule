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

  fs.rmSync(appFilesDir, { recursive: true, force: true })
  await app.close()
  console.log(process.exitCode ? '\n存在失败项' : '\n全部通过')
})().catch((e) => { console.error('SMOKE FAIL', e); process.exit(1) })
