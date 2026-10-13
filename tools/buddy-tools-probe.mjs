/**
 * 探针：CodeBuddy 服务端到底支不支持 function calling（tools 参数）。
 *
 * 为什么必须实测：安卓端的 agent 工具循环完全建立在「服务端接受 tools 并回
 * tool_calls」这个前提上。若服务端忽略 tools，模型永远不会调工具，功能就是
 * 空中楼阁 —— 而这一点无法从任何文档或注释里读出来，只能发一次真请求。
 *
 * 用法：node buddy-tools-probe.mjs [productId]
 */

import fs from 'node:fs'
import https from 'node:https'
import { PRODUCTS, baseHeaders, authHeaders } from './buddy-client.mjs'

const productId = process.argv[2] || 'buddy'
const product = PRODUCTS[productId]
if (!product) {
  console.error('未知产品：' + productId + '，可选：' + Object.keys(PRODUCTS).join(', '))
  process.exit(1)
}

const credential = JSON.parse(fs.readFileSync(new URL('../credential-buddy.json', import.meta.url), 'utf8'))
const model = process.argv[3] || 'hy4-preview'

const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'kb_search',
      description: '在知识库里检索卡片。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '检索词' },
        },
        required: ['query'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'kb_create_doc',
      description: '新建一篇文档。',
      parameters: {
        type: 'object',
        properties: {
          title: { type: 'string', description: '文档标题' },
        },
        required: ['title'],
      },
    },
  },
]

const messages = [
  { role: 'system', content: 'You are a helpful assistant. 用户提到知识库相关需求时，必须调用工具，不要凭记忆回答。' },
  { role: 'user', content: '帮我在知识库里搜一下「西门子」相关的内容。' },
]

const host = new URL(product.endpoint).hostname
const payload = Buffer.from(JSON.stringify({ model, messages, tools: TOOLS, stream: true }), 'utf8')

const req = https.request({
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
}, (res) => {
  if (res.statusCode !== 200) {
    const chunks = []
    res.on('data', (c) => chunks.push(c))
    res.on('end', () => {
      console.log('[HTTP ' + res.statusCode + '] ' + Buffer.concat(chunks).toString('utf8').slice(0, 400))
      process.exit(2)
    })
    return
  }

  let buf = ''
  let text = ''
  const toolCalls = new Map()
  let sawToolDelta = false

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
      const delta = obj?.choices?.[0]?.delta
      if (!delta) continue
      if (typeof delta.content === 'string') text += delta.content
      if (Array.isArray(delta.tool_calls)) {
        sawToolDelta = true
        for (const one of delta.tool_calls) {
          const idx = one.index ?? 0
          const cur = toolCalls.get(idx) || { id: '', name: '', args: '' }
          if (one.id) cur.id = one.id
          if (one.function?.name) cur.name = one.function.name
          if (one.function?.arguments) cur.args += one.function.arguments
          toolCalls.set(idx, cur)
        }
      }
    }
  })

  res.on('end', () => {
    console.log('=== 结果 ===')
    console.log('模型          : ' + model)
    console.log('正文          : ' + JSON.stringify(text.slice(0, 300)))
    console.log('收到 tool_calls: ' + sawToolDelta)
    if (toolCalls.size > 0) {
      console.log('工具调用明细   :')
      for (const [idx, c] of [...toolCalls.entries()].sort((a, b) => a[0] - b[0])) {
        console.log('  [' + idx + '] id=' + c.id + ' name=' + c.name)
        console.log('       args=' + c.args)
      }
    }
    console.log('')
    if (sawToolDelta && toolCalls.size > 0) {
      console.log('✅ 服务端支持 function calling，agent 工具循环成立。')
    } else {
      console.log('❌ 服务端未返回 tool_calls —— agent 工具循环的前提不成立，需要换方案。')
    }
  })
})

req.setTimeout(120000, () => req.destroy(new Error('timeout')))
req.on('error', (e) => {
  console.error('请求失败：' + e.message)
  process.exit(3)
})
req.write(payload)
req.end()
