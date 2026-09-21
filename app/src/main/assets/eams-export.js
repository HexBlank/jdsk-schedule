/*
 * 几点上课 · 教务课表导出脚本（WebView 注入版）
 * 改编自浏览器用户脚本 scripts/schedule-export.user.js：
 *   - 在教务系统已登录页面同源读取并本地解析课表；
 *   - 不上传账号、密码或 Cookie；
 *   - 解析完成后通过 window.ZSJBridge 交回原生 App（而非下载文件）。
 * baseUrl() 依据当前页动态保留 /eams/ 之前的代理前缀，不写死代理加密段。
 */
(function () {
  'use strict';

  if (window.ZSJExport && window.ZSJExport.__installed) {
    window.ZSJExport.installButton();
    return;
  }

  var TIME_SLOTS = [
    ['07:50', '08:35'], ['08:45', '09:30'], ['09:50', '10:35'], ['10:45', '11:30'],
    ['11:31', '12:15'], ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'],
    ['16:55', '17:40'], ['19:15', '20:00'], ['20:10', '20:55'], ['21:05', '21:50']
  ].map(function (t, i) { return { number: i + 1, startTime: t[0], endTime: t[1] }; });

  function baseUrl() {
    var url = new URL(window.location.href);
    var index = url.pathname.indexOf('/eams/');
    return index >= 0 ? url.origin + url.pathname.slice(0, index) : url.origin;
  }

  function eamsPath(path) { return baseUrl() + '/eams' + path; }

  function splitArgs(source) {
    var args = [], current = '', depth = 0, quote = null, escaped = false;
    for (var i = 0; i < source.length; i += 1) {
      var ch = source[i];
      if (quote) {
        current += ch;
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") { quote = ch; current += ch; }
      else if ('([{'.indexOf(ch) >= 0) { depth += 1; current += ch; }
      else if (')]}'.indexOf(ch) >= 0) { depth -= 1; current += ch; }
      else if (ch === ',' && depth === 0) { args.push(current.trim()); current = ''; }
      else current += ch;
    }
    if (current.trim()) args.push(current.trim());
    return args;
  }

  function literal(value) {
    if (!value || value.length < 2) return value || '';
    if (value.charAt(0) === '"' && value.charAt(value.length - 1) === '"') {
      try { return JSON.parse(value); } catch (e) { return value.slice(1, -1); }
    }
    if (value.charAt(0) === "'" && value.charAt(value.length - 1) === "'") return value.slice(1, -1);
    return value;
  }

  function arg(value, teachers, assistant) {
    if (!value || value === 'null') return '';
    if (value.indexOf('actTeacherName') >= 0) return teachers.join(',');
    if (value.indexOf('actTeacherId') >= 0) return '';
    if (value.indexOf('assistantName') >= 0) return assistant;
    return literal(value)
      .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  }

  function merge(courses) {
    var list = courses.map(function (c) {
      var seen = {}; var weeks = [];
      c.weeks.forEach(function (w) { if (!seen[w]) { seen[w] = 1; weeks.push(w); } });
      weeks.sort(function (a, b) { return a - b; });
      return { name: c.name, teacher: c.teacher, position: c.position, day: c.day, startSection: c.startSection, endSection: c.endSection, weeks: weeks };
    });
    function cmp(a, b) {
      return a.name.localeCompare(b.name) || a.teacher.localeCompare(b.teacher) ||
        a.position.localeCompare(b.position) || a.day - b.day ||
        a.weeks.join(',').localeCompare(b.weeks.join(',')) || a.startSection - b.startSection;
    }
    list.sort(cmp);
    var firstPass = [], current = list[0];
    for (var i = 1; i < list.length; i += 1) {
      var next = list[i];
      var same = current.name === next.name && current.teacher === next.teacher &&
        current.position === next.position && current.day === next.day &&
        current.weeks.join(',') === next.weeks.join(',');
      if (same && current.endSection + 1 === next.startSection) current.endSection = next.endSection;
      else if (!(same && current.startSection === next.startSection && current.endSection === next.endSection)) {
        firstPass.push(current); current = next;
      }
    }
    firstPass.push(current);
    firstPass.sort(function (a, b) {
      return a.name.localeCompare(b.name) || a.teacher.localeCompare(b.teacher) ||
        a.position.localeCompare(b.position) || a.day - b.day ||
        a.startSection - b.startSection || a.endSection - b.endSection;
    });
    var result = []; current = firstPass[0];
    for (var j = 1; j < firstPass.length; j += 1) {
      var n2 = firstPass[j];
      var sameSlot = current.name === n2.name && current.teacher === n2.teacher &&
        current.position === n2.position && current.day === n2.day &&
        current.startSection === n2.startSection && current.endSection === n2.endSection;
      if (sameSlot) {
        var set = {}; var mergedWeeks = [];
        current.weeks.concat(n2.weeks).forEach(function (w) { if (!set[w]) { set[w] = 1; mergedWeeks.push(w); } });
        mergedWeeks.sort(function (a, b) { return a - b; });
        current.weeks = mergedWeeks;
      } else { result.push(current); current = n2; }
    }
    result.push(current);
    return result;
  }

  function parse(html) {
    var unit = html.match(/var\s+unitCount\s*=\s*(\d+)/);
    var marshal = html.match(/marshalTable\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)/);
    if (!unit || !marshal) throw new Error('这个页面里没有课表数据');
    var unitCount = Number(unit[1]);
    var from = Number(marshal[1]);
    var startWeek = Number(marshal[2]);
    var endWeek = Number(marshal[3]);
    var segments = html.split('new TaskActivity(');
    var raw = [];
    for (var index = 1; index < segments.length; index += 1) {
      var segment = segments[index];
      var callEnd = segment.indexOf(');');
      if (callEnd < 0) continue;
      var occupied = [];
      var reIndex = /index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;/g;
      var rest = segment.slice(callEnd + 2); var m;
      while ((m = reIndex.exec(rest)) !== null) occupied.push(m);
      if (!occupied.length) continue;
      var previous = segments[index - 1];
      var declarations = []; var reT = /var\s+actTeachers\s*=\s*\[([^\]]*)\]/g; var tm;
      while ((tm = reT.exec(previous)) !== null) declarations.push(tm);
      var declaration = declarations[declarations.length - 1];
      var teachers = [];
      if (declaration) {
        var reN = /name\s*:\s*"((?:\\.|[^"])*)"/g; var nm;
        while ((nm = reN.exec(declaration[1])) !== null) teachers.push(literal('"' + nm[1] + '"'));
      }
      var assistants = []; var reA = /var\s+assistantName\s*=\s*"((?:\\.|[^"])*)"/g; var am;
      while ((am = reA.exec(previous)) !== null) assistants.push(am);
      var assistant = assistants.length ? literal('"' + assistants[assistants.length - 1][1] + '"') : '';
      var args = splitArgs(segment.slice(0, callEnd));
      if (args.length < 7) continue;
      var valid = arg(args[6], teachers, assistant);
      if (!/^[01]+$/.test(valid)) continue;
      var validStart = from + startWeek - 2;
      if (valid.slice(0, validStart).indexOf('1') >= 0 && valid.slice(validStart).indexOf('1') < 0) {
        valid = valid.slice(1) + '0';
      }
      var weeks = [];
      for (var week = startWeek; week <= endWeek; week += 1) {
        if (valid[from + week - 2] === '1') weeks.push(week);
      }
      if (!weeks.length) continue;
      var name = arg(args[3], teachers, assistant).replace(/\s*[（(][0-9A-Za-z.]{3,}[）)]\s*$/, '');
      occupied.forEach(function (match) {
        var day = Number(match[1]) + 1;
        var section = Number(match[2]) + 1;
        if (day < 1 || day > 7 || section < 1 || section > unitCount) return;
        raw.push({
          name: name,
          teacher: [arg(args[1], teachers, assistant), assistant].filter(Boolean).join(','),
          position: arg(args[5], teachers, assistant),
          day: day, startSection: section, endSection: section, weeks: weeks.slice()
        });
      });
    }
    if (!raw.length) throw new Error('这学期没有解析到任何课程');
    return { courses: merge(raw), totalWeeks: endWeek };
  }

  function semesterFrom(html) {
    // 学期 id 提取（按可靠性排序，全部失败返回 null）：
    // ① 页面学期下拉框中被选中的项 ② 隐藏域 ③ 当前页 URL 参数
    // ④ 校历控件 semesterCalendar({...value}) ⑤ Cookie
    var select = html.match(/<select[^>]*name=["']semester\.id["'][^>]*>([\s\S]*?)<\/select>/i);
    if (select) {
      var optionRe = /<option\b[^>]*>/gi, om;
      while ((om = optionRe.exec(select[1])) !== null) {
        var value = (om[0].match(/value=["'](\d+)["']/i) || [])[1];
        if (value && /\bselected\b/i.test(om[0])) return value;
      }
    }
    var hidden = html.match(/<input[^>]*name=["']semester\.id["'][^>]*value=["'](\d+)["']/i) ||
                 html.match(/<input[^>]*value=["'](\d+)["'][^>]*name=["']semester\.id["']/i);
    if (hidden) return hidden[1];
    var fromUrl = (window.location.search || '').match(/[?&]semester\.id=(\d+)/);
    if (fromUrl) return fromUrl[1];
    var calendar = html.match(/semesterCalendar\(\{[^}]*?value:\s*"(\d+)"/);
    if (calendar) return calendar[1];
    var cookie = (document.cookie || '').match(/semester\.id=(\d+)/);
    if (cookie) return cookie[1];
    return null;
  }

  function studentInfo() {
    var htmlPromise;
    if (/courseTableForStd\.action/.test(location.href)) htmlPromise = Promise.resolve(document.documentElement.outerHTML);
    else htmlPromise = fetch(eamsPath('/courseTableForStd.action'), { credentials: 'include' }).then(function (r) { return r.text(); });
    return htmlPromise.then(function (html) {
      var ids = []; var reId = /addInput\(form,"ids","(\d+)"\)/g; var im;
      while ((im = reId.exec(html)) !== null) ids.push(im[1]);
      var semesterId = semesterFrom(html);
      if (!ids.length || !semesterId) throw new Error('没读到学生或学期信息（可能还没登录）');
      return { id: ids[0], semesterId: semesterId };
    });
  }

  function fetchCourseTable(info) {
    return fetch(eamsPath('/courseTableForStd!courseTable.action'), {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
      body: 'ignoreHead=1&setting.kind=std&startWeek=&semester.id=' + encodeURIComponent(info.semesterId) + '&ids=' + encodeURIComponent(info.id)
    }).then(function (response) {
      if (!response.ok) throw new Error('教务接口请求失败（HTTP ' + response.status + '）');
      return response.text();
    });
  }

  /**
   * 调用原生桥，返回是否真的送达。
   * 以前这里失败是静默的：桥不存在时脚本照样把按钮改成「已读取 N 个时段」，
   * 用户看着像成功、App 里却什么都没发生。现在由调用方按返回值决定兜底。
   */
  function bridge(name, payload) {
    try {
      if (!window.ZSJBridge || typeof window.ZSJBridge[name] !== 'function') return false;
      window.ZSJBridge[name](payload || '');
      return true;
    } catch (e) {
      return false;
    }
  }

  function removePanel() {
    var old = document.getElementById('zsj-export-panel');
    if (old && old.parentNode) old.parentNode.removeChild(old);
  }

  /** 页面内提示卡片：桥失效时原生弹窗也弹不出来，话只能在网页上说。 */
  function showPanel(title, message, json) {
    if (!document.body) return;
    removePanel();
    var box = document.createElement('div');
    box.id = 'zsj-export-panel';
    Object.assign(box.style, {
      position: 'fixed', zIndex: '2147483646', left: '12px', right: '12px', bottom: '80px',
      padding: '14px 16px', background: '#fffdf7', color: '#17322d', borderRadius: '14px',
      border: '1px solid rgba(23,50,45,.12)', boxShadow: '0 12px 30px rgba(0,0,0,.26)',
      fontSize: '14px', lineHeight: '1.6', maxHeight: '56vh', overflowY: 'auto'
    });

    var heading = document.createElement('div');
    heading.textContent = title;
    Object.assign(heading.style, { fontWeight: '700', fontSize: '15px', marginBottom: '6px' });
    box.appendChild(heading);

    var body = document.createElement('div');
    body.textContent = message;
    Object.assign(body.style, { color: '#41564f', whiteSpace: 'pre-line' });
    box.appendChild(body);

    if (json) {
      var area = document.createElement('textarea');
      area.value = json;
      area.readOnly = true;
      Object.assign(area.style, {
        width: '100%', height: '82px', marginTop: '10px', padding: '8px', boxSizing: 'border-box',
        fontSize: '12px', borderRadius: '10px', border: '1px solid rgba(23,50,45,.18)',
        background: '#f5f2ea', color: '#17322d'
      });
      box.appendChild(area);

      var copy = document.createElement('button');
      copy.textContent = '复制课表数据';
      Object.assign(copy.style, {
        marginTop: '10px', marginRight: '8px', padding: '9px 14px', border: '0', borderRadius: '11px',
        background: '#17322d', color: '#fffdf7', fontSize: '13px', fontWeight: '700'
      });
      copy.addEventListener('click', function () {
        copy.textContent = copyText(area) ? '已复制，回 App 点「读取剪贴板」' : '复制失败，请长按文本框全选';
      });
      box.appendChild(copy);
    }

    var close = document.createElement('button');
    close.textContent = '关闭';
    Object.assign(close.style, {
      marginTop: '10px', padding: '9px 14px', border: '1px solid rgba(23,50,45,.2)',
      borderRadius: '11px', background: 'transparent', color: '#41564f', fontSize: '13px'
    });
    close.addEventListener('click', removePanel);
    box.appendChild(close);

    document.body.appendChild(box);
  }

  function copyText(area) {
    try {
      area.focus();
      area.select();
      if (area.setSelectionRange) area.setSelectionRange(0, area.value.length);
      return document.execCommand('copy');
    } catch (e) {
      return false;
    }
  }

  function run() {
    var button = window.ZSJExport.__button;
    removePanel();
    if (button) { button.disabled = true; button.textContent = '正在读取课表…'; }
    bridge('onLog', 'start');
    studentInfo()
      .then(fetchCourseTable)
      .then(function (html) {
        var parsed = parse(html);
        var pkg = {
          format: 'zhusijiao-schedule', version: 1, school: '',
          name: '我的课表', semesterStart: '', totalWeeks: parsed.totalWeeks,
          timeSlots: TIME_SLOTS, courses: parsed.courses
        };
        var json = JSON.stringify(pkg);
        if (bridge('onSchedule', json)) {
          if (button) button.textContent = '已读取 ' + parsed.courses.length + ' 个时段';
          return;
        }
        // 读到了但交不回 App（桥失效）：把数据直接交到用户手里，别让人卡在「读完没反应」
        if (button) button.textContent = '读到了，请按提示操作';
        showPanel(
          '课表已读到 ' + parsed.courses.length + ' 个时段，但没能自动交回 App',
          '点下面的「复制课表数据」，回到 App 的导入页点「读取剪贴板」就能接着导入。\n'
            + '复制没反应时，可以长按下面的文本框全选再复制。',
          json
        );
      })
      .catch(function (error) {
        var reason = (error && error.message) || String(error);
        if (button) button.textContent = '重新导入课表';
        // 桥可用就交给 App 弹原生提示，否则只能在网页上说明
        if (!bridge('onError', reason)) showPanel('没能读取课表', reason + '\n' + nextStep(reason));
      })
      .then(function () { if (button) button.disabled = false; });
  }

  /** 按失败原因给一句可操作的下一步，避免只甩一个错误名词。 */
  function nextStep(reason) {
    if (reason.indexOf('登录') >= 0 || reason.indexOf('学生或学期') >= 0) {
      return '请先在本页登录教务系统，登录后再点「导入课表」。';
    }
    if (reason.indexOf('课表数据') >= 0) {
      return '请先在教务系统里打开「学生课表 / 我的课表」页面，再点「导入课表」。';
    }
    if (reason.indexOf('请求失败') >= 0) {
      return '多半是登录已过期或校园网不稳定：重新登录教务系统后再试一次。';
    }
    return '可以重新登录教务系统、回到学生课表页后再试一次。';
  }

  function installButton() {
    if (!document.body) return;
    var existing = document.getElementById('zsj-export-btn');
    if (existing) { window.ZSJExport.__button = existing; return; }
    var button = document.createElement('button');
    button.id = 'zsj-export-btn';
    button.textContent = '导入课表';
    Object.assign(button.style, {
      position: 'fixed', zIndex: '2147483647', right: '18px', bottom: '22px', padding: '12px 18px',
      border: '0', borderRadius: '16px', color: '#fffdf7', background: '#17322d',
      boxShadow: '0 10px 26px rgba(0,0,0,.24)', fontSize: '15px', fontWeight: '700'
    });
    button.addEventListener('click', function () { run(); });
    document.body.appendChild(button);
    window.ZSJExport.__button = button;
  }

  window.ZSJExport = { __installed: true, run: run, installButton: installButton, __button: null };
  installButton();
})();
