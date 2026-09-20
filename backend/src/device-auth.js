const { httpError } = require('./validation')
const crypto = require('node:crypto')

// 原生 App 的匿名设备登录：客户端首次启动生成稳定 UUID，服务端据此派生 openid 并签发 JWT。
// 信任模型与「持有 JWT 即本人」一致；deviceId 相当于长期凭证，仅在设备本地与自有 API 之间使用。
const DEVICE_ID_PATTERN = /^[A-Za-z0-9-]{8,64}$/

function createDeviceAuth(config) {
  function resolveOpenid(deviceId) {
    if (!config.enableDeviceAuth) {
      throw httpError(404, 'NOT_FOUND', '接口不存在')
    }
    const raw = String(deviceId || '').trim()
    if (!DEVICE_ID_PATTERN.test(raw)) {
      throw httpError(400, 'INVALID_DEVICE_ID', '设备标识无效')
    }
    // 数据库不保存客户端长期凭据原文；泄露数据库也无法反推出 deviceId。
    const subject = crypto.createHmac('sha256', config.jwtSecret).update(raw, 'utf8').digest('hex')
    return { openid: `app:v2:${subject}`, legacyOpenid: `app:${raw}` }
  }
  return { resolveOpenid }
}

module.exports = { createDeviceAuth }
