/**
 * 端到端自检（通用）：登录态 → 模型目录 → 流式对话。
 *
 * 用法：node tools/buddy-check.mjs buddy
 */

import { readFileSync } from 'node:fs'
import { PRODUCTS, chatStream, fetchModels } from './buddy-client.mjs'

const id = process.argv[2] || 'buddy'
const product = PRODUCTS[id]
const cred = JSON.parse(readFileSync(new URL('../credential-' + id + '.json', import.meta.url), 'utf8'))

console.log('[product] ' + product.label)
console.log('[cred] access_token ' + String(cred.access_token).length + ' 字符，refresh_token ' + (cred.refresh_token ? '有' : '无'))

const cat = await fetchModels(product, cred)
console.log('[models] scoped=' + cat.scoped + ' config=' + cat.config + ' 并集=' + cat.ids.length)
if (cat.ids.length) console.log('[models] 前 12 个：' + cat.ids.slice(0, 12).join(', '))

// 中国版与国际版的模型池不同，各给一份候选。
const fallback = id === 'workbuddy'
  ? ['deepseek-v4.1-flash', 'hy4-preview', 'hy3', 'glm-5.3']
  : ['deepseek-v4.1-flash', 'hy4-preview', 'hy3', 'glm-5.3', 'deepseek-v4-flash']
// 模型池里挑一个可用的：限流(429/6004)就换下一个。
// 服务端的 429 文案本身就建议「切换其他模型继续使用」，所以这不是权宜之计。
const candidates = [...fallback.filter((m) => cat.ids.includes(m)), ...cat.ids.filter((m) => !/^(auto|default)$/.test(m))]

console.log('')
let ok = null
for (const model of candidates.slice(0, 8)) {
  process.stdout.write('[chat] ' + model + ' … ')
  let acc = ''
  try {
    acc = await chatStream(product, cred, model, [{ role: 'user', content: '只回复两个字：收到' }], () => {})
    console.log('✅ 回复：' + acc.slice(0, 50).replace(/\n/g, ' '))
    ok = model
    break
  } catch (e) {
    const m = e.message
    if (m.includes('6004') || m.includes('429')) { console.log('限流，换下一个'); continue }
    console.log('失败：' + m.slice(0, 120))
  }
}

console.log('')
console.log(ok ? '[done] 端到端可用，模型 = ' + ok : '[done] 全部候选都被限流或失败')
