const path = require('node:path')

function readConfig(overrides = {}) {
  const raw = { ...process.env, ...overrides }
  const config = {
    nodeEnv: raw.NODE_ENV || 'development',
    host: raw.HOST || '127.0.0.1',
    port: Number(raw.PORT || 3200),
    databasePath: raw.DATABASE_PATH === ':memory:' ? ':memory:' : path.resolve(raw.DATABASE_PATH || './data/zhusijiao.db'),
    jwtSecret: raw.JWT_SECRET || 'development-only-secret-change-before-production',
    enableDevAuth: String(raw.ENABLE_DEV_AUTH || 'false').toLowerCase() === 'true',
    // 原生 App 的设备匿名登录，默认开启（这是 App 的正式登录方式，不同于仅联调用的 dev 登录）。
    enableDeviceAuth: String(raw.ENABLE_DEVICE_AUTH === undefined ? 'true' : raw.ENABLE_DEVICE_AUTH).toLowerCase() !== 'false',
    trustProxy: raw.TRUST_PROXY || '127.0.0.1',
    appFilesDir: raw.APP_FILES_DIR ? path.resolve(raw.APP_FILES_DIR) : path.resolve(__dirname, '../public/app'),
    maxSchedulesPerUser: Number(raw.MAX_SCHEDULES_PER_USER || 30),
    maxSubscriptionsPerUser: Number(raw.MAX_SUBSCRIPTIONS_PER_USER || 100),
    logLevel: raw.LOG_LEVEL || 'info',
    // 官网域名（CORS 允许跨域读取 /app/release.json 等公开接口）。
    // 多个域名用逗号分隔，例如：https://jdsk.example.com,https://www.jdsk.example.com
    // 留空 = 不返回 CORS 头（与之前行为一致，官网与 API 同域反代时无需设置）。
    siteOrigins: raw.SITE_ORIGINS
      ? raw.SITE_ORIGINS.split(',').map(s => s.trim()).filter(Boolean)
      : []
  }

  if (config.nodeEnv === 'production') {
    if (config.jwtSecret.length < 32 || config.jwtSecret.includes('development-only')) {
      throw new Error('生产环境 JWT_SECRET 必须是至少 32 位的随机字符串')
    }
  }
  if (!Number.isInteger(config.port) || config.port < 1 || config.port > 65535) {
    throw new Error('PORT 必须是有效端口')
  }
  if (!Number.isInteger(config.maxSchedulesPerUser) || config.maxSchedulesPerUser < 1 ||
      !Number.isInteger(config.maxSubscriptionsPerUser) || config.maxSubscriptionsPerUser < 1) {
    throw new Error('课表与订阅配额必须是正整数')
  }
  return config
}

module.exports = { readConfig }
