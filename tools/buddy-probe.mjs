/**
 * 登录探针（通用，按产品参数化）
 *
 * 用法：
 *   node tools/buddy-probe.mjs buddy        # CodeBuddy（腾讯中国版）
 *   node tools/buddy-probe.mjs workbuddy    # WorkBuddy（国际版）
 *
 * 流程与原 workbuddy-probe 一致，但现在两个产品共用一份实现 ——
 * 差异只在 PRODUCTS 配置里。
 */

import { writeFileSync } from 'node:fs'
import { PRODUCTS, fetchAuthState, decorateLoginUrl, pollToken, pollAccount } from './buddy-client.mjs'

const id = process.argv[2] || 'buddy'
const product = PRODUCTS[id]
if (!product) {
  console.error('未知产品：' + id + '（可选 ' + Object.keys(PRODUCTS).join(' / ') + '）')
  process.exit(1)
}

console.log('[product] ' + product.label + '  endpoint=' + product.endpoint + '  platform=' + product.platform)

const { state, authUrl } = await fetchAuthState(product)
console.log('[state] state = ' + state)

const url = decorateLoginUrl(product, authUrl)
console.log('')
console.log('=== 请用浏览器打开下面的地址完成登录 ===')
console.log(url)
console.log('=====================================')
console.log('')

const token = await pollToken(product, state)
console.log('[token] 已获取，accessToken 长度 ' + String(token.accessToken || '').length)

const account = await pollAccount(product, state)
console.log('[account] ' + (account ? JSON.stringify(account).slice(0, 200) : '未取到（不影响 token 使用）'))

const credential = {
  product: product.id,
  access_token: token.accessToken,
  refresh_token: token.refreshToken,
  expires_at: token.expiresAt || '',
  domain: token.domain || product.apiDomain,
  user_id: (account && account.uid) || '',
  nickname: (account && account.nickname) || '',
}
const out = 'credential-' + product.id + '.json'
writeFileSync(out, JSON.stringify(credential, null, 2), 'utf8')
console.log('')
console.log('[done] 凭据已写入 ' + out)
