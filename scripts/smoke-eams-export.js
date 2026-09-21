/*
 * 教务导出脚本（app/src/main/assets/eams-export.js）的冒烟测试。
 *
 * 这段脚本跑在教务页面的 WebView 里，没法用 Gradle 单测覆盖，这里用 node 造一个最小的
 * window/document/fetch 环境把它跑起来，守住两条最要紧的行为：
 *   ① 桥正常   → 解析结果经 onSchedule 交回 App；
 *   ② 桥失效   → 绝不能装作成功。必须弹出兜底卡片并附上可复制的 JSON，
 *                 否则用户看到的就是「已读取 N 个时段」之后再无下文（1.2.21 及更早的真实反馈）。
 *
 * 跑法：node scripts/smoke-eams-export.js
 */
const fs = require('fs')
const path = require('path')
const vm = require('vm')

const TARGET = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'eams-export.js')
const SCRIPT = fs.readFileSync(TARGET, 'utf8')

const STUDENT_HTML = `
  <html><body>
    <script>addInput(form,"ids","12345")</script>
    <select name="semester.id"><option value="88" selected>2026 秋</option></select>
  </body></html>`

// 最小可解析课表：两门课、含 unitCount / marshalTable / TaskActivity / index 占位
const COURSE_HTML = `
  var unitCount = 12;
  marshalTable(1,1,20);
  var actTeachers = [{id:1,name:"张三"}];
  var activity = new TaskActivity("t1","张三","c1","高等数学","code","A101","11111111111111111111");
  index =0*unitCount+0;
  index =0*unitCount+1;
  var activity2 = new TaskActivity("t2","李四","c2","大学英语","code","B202","10101010101010101010");
  index =2*unitCount+4;
`

function makeElement(tag) {
  const el = {
    tagName: tag, id: '', style: {}, value: '', textContent: '', readOnly: false,
    children: [], parentNode: null, listeners: {},
    appendChild(child) { child.parentNode = el; el.children.push(child); return child },
    removeChild(child) { el.children = el.children.filter((c) => c !== child); child.parentNode = null },
    addEventListener(name, fn) { el.listeners[name] = fn },
    focus() {}, select() {}, setSelectionRange() {}
  }
  return el
}

function run({ withBridge }) {
  const body = makeElement('body')
  const calls = { onSchedule: [], onError: [], onLog: [] }

  const documentMock = {
    body,
    documentElement: { outerHTML: '<html></html>' },
    cookie: '',
    createElement: makeElement,
    getElementById(id) {
      const walk = (node) => node.children.reduce((found, c) => found || (c.id === id ? c : walk(c)), null)
      return walk(body)
    },
    execCommand: () => true
  }

  const windowMock = {
    location: { href: 'https://webvpn.example.edu.cn/http/abc/eams/home.action', search: '' },
    document: documentMock,
    URL,
    Promise,
    fetch: (url) => Promise.resolve({
      ok: true,
      status: 200,
      text: () => Promise.resolve(/courseTable\.action/.test(url) ? COURSE_HTML : STUDENT_HTML)
    })
  }
  windowMock.window = windowMock
  if (withBridge) {
    windowMock.ZSJBridge = {
      onSchedule: (json) => calls.onSchedule.push(json),
      onError: (msg) => calls.onError.push(msg),
      onLog: (msg) => calls.onLog.push(msg)
    }
  }

  const ctx = vm.createContext(windowMock)
  ctx.document = documentMock
  ctx.fetch = windowMock.fetch
  ctx.location = windowMock.location
  vm.runInContext(SCRIPT, ctx)

  return new Promise((resolve) => {
    ctx.window.ZSJExport.run()
    // 两次 fetch + 若干 then，留够微任务轮转
    setImmediate(() => setImmediate(() => setImmediate(() => {
      const panel = documentMock.getElementById('zsj-export-panel')
      resolve({
        calls,
        button: ctx.window.ZSJExport.__button.textContent,
        panelTitle: panel ? panel.children[0].textContent : null,
        panelJson: panel ? (panel.children.find((c) => c.tagName === 'textarea') || {}).value : null
      })
    })))
  })
}

;(async () => {
  let failed = 0
  const check = (label, ok, detail) => {
    console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  → ' + detail : ''))
    if (!ok) failed += 1
  }

  const ok = await run({ withBridge: true })
  check('桥正常：onSchedule 收到 JSON', ok.calls.onSchedule.length === 1)
  const pkg = ok.calls.onSchedule[0] ? JSON.parse(ok.calls.onSchedule[0]) : null
  check('桥正常：解析出课程', !!pkg && pkg.courses.length === 2, pkg && pkg.courses.map((c) => c.name).join('、'))
  check('桥正常：按钮显示已读取', /已读取 \d+ 个时段/.test(ok.button), ok.button)
  check('桥正常：不弹兜底卡片', ok.panelTitle === null)
  check('桥正常：上报了开始日志', ok.calls.onLog[0] === 'start')

  const broken = await run({ withBridge: false })
  check('桥失效：不再谎称已读取', !/^已读取/.test(broken.button), broken.button)
  check('桥失效：弹出兜底卡片', !!broken.panelTitle, broken.panelTitle)
  check('桥失效：卡片里带着可复制的 JSON', !!broken.panelJson && JSON.parse(broken.panelJson).courses.length === 2)

  console.log(failed === 0 ? '\n全部通过' : `\n${failed} 项失败`)
  process.exit(failed === 0 ? 0 : 1)
})()
