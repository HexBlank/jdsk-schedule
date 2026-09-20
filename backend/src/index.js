require('dotenv').config({ quiet: true })

const { readConfig } = require('./config')
const { openDatabase } = require('./db')
const { buildApp } = require('./app')

async function main() {
  const config = readConfig()
  const db = openDatabase(config.databasePath)
  const app = buildApp({ config, db })

  const shutdown = async (signal) => {
    app.log.info({ signal }, '正在停止服务')
    await app.close()
    process.exit(0)
  }
  process.once('SIGINT', shutdown)
  process.once('SIGTERM', shutdown)

  await app.listen({ host: config.host, port: config.port })
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
