/**
 * CodeBuddy / WorkBuddy 通用客户端（Node 端参考实现 = 协议的可执行规格）
 *
 * 两个产品同源：共用认证协议（external-link-v2）与同一套 /v2/plugin/* 路径，
 * 差异**全部**收敛在 PRODUCTS 里。安卓端照这份结构实现即可。
 *
 * 协议细节来自 E:\hell\dsh\plugins\codearts\src：
 *   product.ts / buddy.ts / buddy-auth.ts / buddy-oauth.ts / buddy-adapter.ts
 */

import https from 'node:https'
import net from 'node:net'
import tls from 'node:tls'
import crypto from 'node:crypto'

/**
 * 一个产品的全部差异配置。
 *
 * ⚠️ userAgentByModelFamily 为空数组表示「不做分档」，全部模型沿用 userAgent
 * —— 中国版只有一条产品线，按模型分档是国际版特有的需求。
 *
 * ⚠️ appendSessionParams 决定登录 URL 是否追加 version/loginSessionId。
 * 中国版为 false：那两个参数是国际版登录站的要求，多发给中国版会被当成异常。
 */
export const PRODUCTS = {
  buddy: {
    id: 'buddy',
    label: 'CodeBuddy（腾讯）',
    endpoint: 'https://copilot.tencent.com',
    apiDomain: 'copilot.tencent.com',
    platform: 'ide',
    productCode: 'codebuddy',
    attributionName: 'CodeBuddy',
    clientVersion: '1.106.1',
    userAgent: 'CodeBuddyIDE/1.106.1',
    userAgentByModelFamily: [],
    appendSessionParams: false,
  },
  workbuddy: {
    id: 'workbuddy',
    label: 'WorkBuddy（国际版）',
    endpoint: 'https://www.workbuddy.ai',
    apiDomain: 'www.workbuddy.ai',
    platform: 'workbuddy-ai',
    productCode: 'workbuddy',
    attributionName: 'WorkBuddy',
    clientVersion: '5.5.2',
    userAgent: 'WorkBuddy/5.5.2 WorkBuddy/5.5.2 CLI/5.5.2',
    userAgentByModelFamily: [
      { match: 'gpt-', ua: 'WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2' },
      { match: 'gemini-', ua: 'WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2' },
      { match: 'claude-', ua: 'WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2' },
    ],
    appendSessionParams: true,
  },
}

/** 按模型族解析 UA；无匹配规则时回退到产品默认 UA。 */
export function userAgentFor(product, model) {
  const m = String(model || '')
  for (const rule of product.userAgentByModelFamily) {
    if (m.startsWith(rule.match)) return rule.ua
  }
  return product.userAgent
}

/**
 * 所有请求共用的身份头。
 *
 * ⚠️ X-Product 是**归属名（产品名）**，不是部署类型。
 * 历史实现发成 `SaaS` 会让后台「使用端」列归因不到（不报错，只是记错账）。
 * 依据：buddy-adapter.ts:2019-2025 的注释。
 */
export function baseHeaders(product, model) {
  return {
    'X-Domain': product.apiDomain,
    'X-Product-Code': product.productCode,
    'X-Product': product.attributionName,
    'X-IDE-Name': product.attributionName,
    'X-IDE-Type': product.attributionName,
    'X-IDE-Version': product.clientVersion,
    'X-Agent-Purpose': 'conversation',
    'User-Agent': userAgentFor(product, model),
  }
}

function makeAgent(host, proxy) {
  const agent = new https.Agent({ keepAlive: false, maxSockets: 8 })
  agent.createConnection = (opts, cb) => {
    if (!proxy) {
      const s = tls.connect({ host, port: 443, servername: host })
      s.once('secureConnect', () => cb(null, s))
      s.once('error', (e) => cb(e))
      return s
    }
    const raw = net.connect(proxy.port, proxy.host)
    raw.once('error', (e) => cb(e))
    raw.once('connect', () => {
      raw.write('CONNECT ' + host + ':443 HTTP/1.1\r\nHost: ' + host + ':443\r\n\r\n')
      const onData = (chunk) => {
        if (!/^HTTP\/1\.[01] 200/.test(chunk.toString('utf8'))) {
          raw.destroy()
          return cb(new Error('proxy CONNECT refused'))
        }
        raw.removeListener('data', onData)
        const s = tls.connect({ socket: raw, servername: host })
        s.once('secureConnect', () => cb(null, s))
        s.once('error', (e) => cb(e))
      }
      raw.on('data', onData)
    })
    return raw
  }
  return agent
}

/** 每个产品各缓存一对 agent（直连 / 代理），避免重复建连接池。 */
const agents = new Map()
function agentFor(product, proxy) {
  const key = product.id + (proxy ? ':proxy' : ':direct')
  let a = agents.get(key)
  if (!a) {
    a = makeAgent(new URL(product.endpoint).hostname, proxy)
    agents.set(key, a)
  }
  return a
}

/** 发一个请求。proxy 为 null 时直连。 */
export function request(product, method, path, headers, bodyObj, proxy = null) {
  const host = new URL(product.endpoint).hostname
  return new Promise((resolve, reject) => {
    const payload = bodyObj === undefined ? null : Buffer.from(JSON.stringify(bodyObj), 'utf8')
    const req = https.request(
      {
        host,
        port: 443,
        method,
        path,
        headers: {
          Host: host,
          Accept: 'application/json',
          'Content-Type': 'application/json',
          ...headers,
          ...(payload ? { 'Content-Length': payload.length } : {}),
        },
        agent: agentFor(product, proxy),
      },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try { json = JSON.parse(text) } catch { /* 非 JSON（如 500 的 HTML） */ }
          resolve({ status: res.statusCode ?? 0, headers: res.headers, text, json })
        })
        res.on('error', reject)
      }
    )
    req.setTimeout(120000, () => req.destroy(new Error('request timeout')))
    req.on('error', reject)
    if (payload) req.write(payload)
    req.end()
  })
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

export function authHeaders(product, credential, model = '') {
  return { ...baseHeaders(product, model), Authorization: 'Bearer ' + credential.access_token }
}

/** 第一步：拿 state 与登录 URL。 */
export async function fetchAuthState(product, proxy = null) {
  const r = await request(
    product,
    'POST',
    '/v2/plugin/auth/state?platform=' + product.platform,
    {
      ...baseHeaders(product, ''),
      'X-No-Authorization': 'true',
      'X-No-User-Id': 'true',
      'X-No-Enterprise-Id': 'true',
      'X-No-Department-Info': 'true',
    },
    {},
    proxy
  )
  const state = r.json?.data?.state
  const authUrl = r.json?.data?.authUrl
  if (r.status !== 200 || !state) {
    throw new Error('auth/state HTTP ' + r.status + ' body=' + r.text.slice(0, 200))
  }
  return { state, authUrl }
}

/** 只在产品要求时给登录 URL 追加 version 与 loginSessionId（只追加，不重建）。 */
export function decorateLoginUrl(product, authUrl) {
  if (!product.appendSessionParams) return authUrl
  try {
    const u = new URL(authUrl)
    u.searchParams.set('version', product.clientVersion)
    u.searchParams.set('loginSessionId', crypto.randomUUID())
    return u.toString()
  } catch { return authUrl }
}

/** 第二步：轮询 token。11217 = 尚未就绪。 */
export async function pollToken(product, state, proxy = null, timeoutMs = 300000) {
  const headers = { 'X-No-Authorization': 'true', 'User-Agent': product.userAgent }
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await sleep(1500)
    let r
    try {
      r = await request(product, 'GET', '/v2/plugin/auth/token?state=' + encodeURIComponent(state), headers, undefined, proxy)
    } catch { continue }
    if (r.status === 200 && r.json?.data) return r.json.data
    const code = r.json?.code
    if (code === 11217 || code === undefined) continue
    throw new Error('auth/token HTTP ' + r.status + ' code=' + code + ' body=' + r.text.slice(0, 200))
  }
  throw new Error('未拿到 token（超时）')
}

/** 第三步：轮询账户信息。12151 = 尚未就绪。取不到不致命。 */
export async function pollAccount(product, state, proxy = null, timeoutMs = 60000) {
  const headers = { 'X-No-Authorization': 'true', 'User-Agent': product.userAgent }
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await sleep(1500)
    let r
    try {
      r = await request(product, 'GET', '/v2/plugin/login/account?state=' + encodeURIComponent(state), headers, undefined, proxy)
    } catch { continue }
    if (r.status === 200 && r.json?.data) return r.json.data
    if (r.json?.code === 12151) continue
    return null
  }
  return null
}

/**
 * 流式聊天。服务端**只接受 stream:true**（传 false 报 11101），
 * 且**首条必须是 system**（否则报 11128）—— 两者都是 PC 端实测确认的硬约束。
 */
export function chatStream(product, credential, model, messages, onDelta, proxy = null) {
  const host = new URL(product.endpoint).hostname
  return new Promise((resolve, reject) => {
    // 首条不是 system 就补一条：调用方不该为此负责。
    const msgs = messages[0]?.role === 'system'
      ? messages
      : [{ role: 'system', content: 'You are a helpful assistant.' }, ...messages]

    const payload = Buffer.from(JSON.stringify({ model, messages: msgs, stream: true }), 'utf8')
    const req = https.request(
      {
        host,
        port: 443,
        method: 'POST',
        path: '/v2/chat/completions',
        headers: {
          Host: host,
          ...authHeaders(product, credential, model),
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'Content-Length': payload.length,
        },
        agent: agentFor(product, proxy),
      },
      (res) => {
        if (res.statusCode !== 200) {
          const chunks = []
          res.on('data', (c) => chunks.push(c))
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8')
            let json = null
            try { json = JSON.parse(text) } catch { /* HTML */ }
            reject(new Error('HTTP ' + res.statusCode + ' ' + (json ? JSON.stringify(json).slice(0, 200) : text.slice(0, 160))))
          })
          return
        }
        let buf = ''
        let full = ''
        res.setEncoding('utf8')
        res.on('data', (chunk) => {
          buf += chunk
          let nl
          while ((nl = buf.indexOf('\n')) >= 0) {
            const line = buf.slice(0, nl).trim()
            buf = buf.slice(nl + 1)
            if (!line.startsWith('data:')) continue
            const data = line.slice(5).trim()
            if (data === '[DONE]') continue
            let obj = null
            try { obj = JSON.parse(data) } catch { continue }
            const piece = obj?.choices?.[0]?.delta?.content
            if (typeof piece === 'string' && piece.length > 0) {
              full += piece
              if (onDelta) onDelta(piece)
            }
          }
        })
        res.on('end', () => resolve(full))
        res.on('error', reject)
      }
    )
    req.setTimeout(180000, () => req.destroy(new Error('stream timeout')))
    req.on('error', reject)
    req.write(payload)
    req.end()
  })
}

/** 模型目录：scoped 主端点 + /v3/config 兜底，**取并集**（两者 id 集合不同）。 */
export async function fetchModels(product, credential, proxy = null) {
  const ids = new Set()
  const take = (json) => {
    const arr = json?.data?.models ?? json?.data?.data?.models ?? json?.models
    if (!Array.isArray(arr)) return 0
    let n = 0
    for (const m of arr) {
      const id = typeof m === 'string' ? m : m && typeof m.id === 'string' ? m.id : null
      if (id) { ids.add(id); n++ }
    }
    return n
  }
  let scoped = 0
  try {
    const s = await request(product, 'GET', '/console/enterprises/personal/models', authHeaders(product, credential), undefined, proxy)
    if (s.status === 200) scoped = take(s.json)
  } catch { /* 该端点对 CLI token 常 500，属已知情况 */ }
  let config = 0
  try {
    const c = await request(product, 'GET', '/v3/config', authHeaders(product, credential), undefined, proxy)
    if (c.status === 200) config = take(c.json)
  } catch { /* 兜底端点也失败时由调用方回退静态表 */ }
  return { ids: [...ids], scoped, config }
}
