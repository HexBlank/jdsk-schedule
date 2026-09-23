const Fastify = require('fastify')
const fs = require('node:fs')
const path = require('node:path')
const cors = require('@fastify/cors')
const helmet = require('@fastify/helmet')
const jwt = require('@fastify/jwt')
const rateLimit = require('@fastify/rate-limit')
const { ensureUser, migrateLegacyDeviceUser } = require('./db')
const { createScheduleService } = require('./schedule-service')
const { createDeviceAuth } = require('./device-auth')
const { httpError } = require('./validation')
const parser = require('./eams-parser')

function buildApp({ config, db, logger = true } = {}) {
  const app = Fastify({
    logger: logger ? { level: config.logLevel } : false,
    trustProxy: config.trustProxy,
    bodyLimit: 2 * 1024 * 1024
  })
  const schedules = createScheduleService(db, config)
  const deviceAuth = createDeviceAuth(config)

  app.register(helmet, { contentSecurityPolicy: false })
  // CORS：默认保持收紧（不返回 Access-Control-Allow-Origin），
  // 配置了 SITE_ORIGINS（官网域名）后仅对这些来源放开。
  // 注意：公开接口（/app/release.json、APK 下载、导出脚本）不含凭据，放开无安全风险；
  // 所有 /api/v1/* 接口仍要求 Authorization 头，不受影响。
  app.register(cors, config.siteOrigins.length > 0
    ? { origin: config.siteOrigins }
    : { origin: false })
  app.register(rateLimit, { max: 120, timeWindow: '1 minute' })
  app.register(jwt, { secret: config.jwtSecret })

  app.decorate('authenticate', async function authenticate(request) {
    try {
      await request.jwtVerify()
    } catch {
      throw httpError(401, 'UNAUTHORIZED', '登录状态已过期')
    }
    request.userRecord = ensureUser(db, request.user.openid)
  })

  app.get('/healthz', async () => ({ ok: true, service: 'zhusijiao-api' }))

  app.get('/tools/schedule-export.user.js', async (request, reply) => {
    const filename = path.resolve(__dirname, '../scripts/schedule-export.user.js')
    reply.header('Cache-Control', 'public, max-age=300')
    reply.type('application/javascript; charset=utf-8')
    return reply.send(fs.createReadStream(filename))
  })

  // 应用更新分发：release.json 与 APK 都放在 APP_FILES_DIR（默认 backend/public/app）。
  // 发布新版本 = 把 APK 丢进该目录 + 更新 release.json；App 端设置页/启动时检查这里。
  const apkNamePattern = /^[\w.-]+\.apk$/i
  app.get('/app/release.json', async (request, reply) => {
    const file = path.join(config.appFilesDir, 'release.json')
    if (!fs.existsSync(file)) throw httpError(404, 'RELEASE_NOT_FOUND', '暂无发布信息')
    reply.header('Cache-Control', 'public, max-age=60')
    reply.type('application/json; charset=utf-8')
    return reply.send(fs.createReadStream(file))
  })

  app.get('/app/download/:file', async (request, reply) => {
    const name = String((request.params && request.params.file) || '')
    if (!apkNamePattern.test(name)) throw httpError(400, 'INVALID_FILE', '文件名不合法')
    const file = path.join(config.appFilesDir, name)
    if (!fs.existsSync(file)) throw httpError(404, 'APK_NOT_FOUND', '安装包不存在，请稍后再试')
    reply.header('Cache-Control', 'public, max-age=3600')
    reply.header('Content-Disposition', `attachment; filename="${name}"`)
    reply.type('application/vnd.android.package-archive')
    return reply.send(fs.createReadStream(file))
  })

  // 多更新通道：backend/public/app/ch/<通道名>/release.json + 同目录 APK 下载。
  // 通道名只允许字母数字下划线横线（拒绝路径穿越）；正式版仍走 /app/release.json。
  // 通道里的 release.json 也可以用 downloadUrl 指向任意外部 APK 地址（如 GitHub Release）。
  const channelPattern = /^[\w-]{1,32}$/
  app.get('/app/ch/:channel/release.json', async (request, reply) => {
    const channel = String(request.params.channel || '')
    if (!channelPattern.test(channel)) throw httpError(400, 'INVALID_CHANNEL', '更新通道不合法')
    const file = path.join(config.appFilesDir, 'ch', channel, 'release.json')
    if (!fs.existsSync(file)) throw httpError(404, 'RELEASE_NOT_FOUND', '该通道暂无发布信息')
    reply.header('Cache-Control', 'public, max-age=60')
    reply.type('application/json; charset=utf-8')
    return reply.send(fs.createReadStream(file))
  })

  app.get('/app/ch/:channel/download/:file', async (request, reply) => {
    const channel = String(request.params.channel || '')
    if (!channelPattern.test(channel)) throw httpError(400, 'INVALID_CHANNEL', '更新通道不合法')
    const name = String((request.params && request.params.file) || '')
    if (!apkNamePattern.test(name)) throw httpError(400, 'INVALID_FILE', '文件名不合法')
    const file = path.join(config.appFilesDir, 'ch', channel, name)
    if (!fs.existsSync(file)) throw httpError(404, 'APK_NOT_FOUND', '安装包不存在，请稍后再试')
    reply.header('Cache-Control', 'public, max-age=3600')
    reply.header('Content-Disposition', `attachment; filename="${name}"`)
    reply.type('application/vnd.android.package-archive')
    return reply.send(fs.createReadStream(file))
  })

  // 匿名设备登录：客户端稳定的设备 id 换取 30 天 JWT，无需账号密码。
  // openid 是 users 表的身份列，取值形如 app:v2:<hmac>，schedules 与权限规则据此关联。
  app.post('/api/v1/auth/device', {
    config: { rateLimit: { max: 30, timeWindow: '1 minute' } }
  }, async (request) => {
    const identity = deviceAuth.resolveOpenid(request.body && request.body.deviceId)
    ensureUser(db, identity.openid)
    migrateLegacyDeviceUser(db, identity.legacyOpenid, identity.openid)
    return {
      token: app.jwt.sign({ openid: identity.openid }, { expiresIn: '30d' })
    }
  })

  app.post('/api/v1/auth/dev', async (request) => {
    if (!config.enableDevAuth || config.nodeEnv === 'production') {
      throw httpError(404, 'NOT_FOUND', '接口不存在')
    }
    const openid = String((request.body && request.body.openid) || 'dev-user').slice(0, 64)
    ensureUser(db, openid)
    return { token: app.jwt.sign({ openid }, { expiresIn: '30d' }) }
  })

  app.get('/api/v1/schedules', { preHandler: app.authenticate }, async (request) => ({
    schedules: schedules.list(request.userRecord.id)
  }))

  app.get('/api/v1/schedules/:id', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.get(request.userRecord.id, request.params.id)
  }))

  app.post('/api/v1/schedules', { preHandler: app.authenticate }, async (request, reply) => {
    const schedule = schedules.save(request.userRecord.id, null, request.body)
    reply.code(201)
    return { schedule }
  })

  app.put('/api/v1/schedules/:id', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.save(request.userRecord.id, request.params.id, request.body)
  }))

  app.delete('/api/v1/schedules/:id', { preHandler: app.authenticate }, async (request, reply) => {
    schedules.remove(request.userRecord.id, request.params.id)
    reply.code(204).send()
  })

  app.post('/api/v1/schedules/:id/adjustments', { preHandler: app.authenticate }, async (request, reply) => {
    const schedule = schedules.createAdjustment(
      request.userRecord.id, request.params.id, request.body, request.body && request.body._expectedRevision
    )
    reply.code(201)
    return { schedule }
  })

  app.put('/api/v1/schedules/:id/adjustments/:adjustmentId', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.updateAdjustment(
      request.userRecord.id,
      request.params.id,
      request.params.adjustmentId,
      request.body,
      request.body && request.body._expectedRevision
    )
  }))

  app.delete('/api/v1/schedules/:id/adjustments/:adjustmentId', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.removeAdjustment(
      request.userRecord.id,
      request.params.id,
      request.params.adjustmentId,
      request.query && request.query.revision
    )
  }))

  app.post('/api/v1/schedules/:id/holidays', { preHandler: app.authenticate }, async (request, reply) => {
    const schedule = schedules.createHoliday(
      request.userRecord.id, request.params.id, request.body, request.body && request.body._expectedRevision
    )
    reply.code(201)
    return { schedule }
  })

  app.delete('/api/v1/schedules/:id/holidays/:holidayId', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.removeHoliday(
      request.userRecord.id,
      request.params.id,
      request.params.holidayId,
      request.query && request.query.revision
    )
  }))

  app.post('/api/v1/schedules/:id/makeups', { preHandler: app.authenticate }, async (request, reply) => {
    const schedule = schedules.createMakeup(
      request.userRecord.id, request.params.id, request.body, request.body && request.body._expectedRevision
    )
    reply.code(201)
    return { schedule }
  })

  app.delete('/api/v1/schedules/:id/makeups/:makeupId', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.removeMakeup(
      request.userRecord.id,
      request.params.id,
      request.params.makeupId,
      request.query && request.query.revision
    )
  }))

  app.post('/api/v1/share/preview', {
    preHandler: app.authenticate,
    config: { rateLimit: { max: 30, timeWindow: '1 minute' } }
  }, async (request) => ({
    schedule: schedules.preview(request.userRecord.id, request.body && request.body.code)
  }))

  app.post('/api/v1/share/join', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.join(request.userRecord.id, request.body && request.body.code)
  }))

  app.post('/api/v1/schedules/:id/share-code', { preHandler: app.authenticate }, async (request) => (
    schedules.rotateCode(request.userRecord.id, request.params.id)
  ))

  app.put('/api/v1/schedules/:id/course-colors', { preHandler: app.authenticate }, async (request) => ({
    schedule: schedules.setCourseColors(
      request.userRecord.id, request.params.id, request.body, request.body && request.body._expectedRevision
    )
  }))

  app.get('/api/v1/schedules/:id/status', { preHandler: app.authenticate }, async (request) => ({
    status: schedules.status(request.userRecord.id, request.params.id)
  }))

  app.delete('/api/v1/schedules/:id/membership', { preHandler: app.authenticate }, async (request, reply) => {
    schedules.leave(request.userRecord.id, request.params.id)
    reply.code(204).send()
  })

  app.delete('/api/v1/me', { preHandler: app.authenticate }, async (request, reply) => {
    db.prepare('DELETE FROM users WHERE id = ?').run(request.userRecord.id)
    reply.code(204).send()
  })

  app.post('/api/v1/imports/parse', { preHandler: app.authenticate }, async (request) => {
    const content = request.body && request.body.content
    if (typeof content !== 'string' || !content.trim() || content.length > 1_800_000) {
      throw httpError(400, 'INVALID_IMPORT', '导入内容为空或超过 1.8MB')
    }
    try {
      return { schedule: parser.parseImportContent(content) }
    } catch (error) {
      throw httpError(400, 'IMPORT_PARSE_FAILED', error.message || '课表解析失败')
    }
  })

  app.setNotFoundHandler(async () => {
    throw httpError(404, 'NOT_FOUND', '接口不存在')
  })

  app.setErrorHandler((error, request, reply) => {
    const statusCode = error.statusCode && error.statusCode >= 400 ? error.statusCode : 500
    if (statusCode >= 500) request.log.error(error)
    reply.code(statusCode).send({
      error: {
        code: error.code || 'INTERNAL_ERROR',
        message: statusCode >= 500 ? '服务器暂时开小差了' : error.message
      }
    })
  })

  app.addHook('onClose', async () => db.close())
  return app
}

module.exports = { buildApp }
