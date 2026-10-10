# 网关助手（Gateway Assistant）

**把你的 Android 手机变成一台私有的 AI 模型聚合服务器。**

手机上装着多个 AI 服务账号，它们的客户端协议各不相同、无法直接在第三方工具中使用。「网关助手」把它们**全部翻译成标准 OpenAI 协议**，暴露在 `http://127.0.0.1:8790/v1`——任何支持 OpenAI 协议的客户端（Cherry Studio、Cline、Continue、curl 脚本……）都能透明使用手机里所有账号的额度。

> 📐 **接盘/二次开发前必读**：[ARCHITECTURE.md](ARCHITECTURE.md) —— 完整架构地图、模块职责、协议陷阱清单、凭证策略铁律、改动守则。

---

## 支持的供应商

| 供应商 | 登录方式 | 区域 | 能力 |
|---|---|---|---|
| **Trae**（字节） | 内嵌网页 | `trae-cn` / `trae-global` | 额度、签到（仅国内） |
| **WorkBuddy**（腾讯 CodeBuddy） | 内嵌网页 | `codebuddy-cn` / `codebuddy-global` | 额度、签到、成长中心（仅国内） |
| **Antigravity**（Google） | 内嵌网页 | — | 额度 |
| **Loomy**（讯飞） | 短信（支持国家码） | — | 额度、任务、邀请码/兑换码 |
| **LongCat**（美团龙猫） | 内嵌网页 / 免登录 | `longcat-cn` / `longcat-global` | 额度 |
| **自定义** | API Key | — | 任意 OpenAI 兼容中转站 |

模型名前缀即路由：`trae-global/glm-5.2`、`codebuddy-cn/glm-5.2`、`longcat-pro` …
不带前缀时按模型名特征自动推导归属供应商。

---

## 核心功能

| 功能 | 说明 |
|---|---|
| **OpenAI 兼容网关** | `/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图 |
| **同款模型 Failover** | 首选供应商失败/无账号时自动切换到其他拥有同款模型的可用供应商 |
| **智能路由** | `provider/model` 前缀 + 别名识别 + 无前缀模型特征推导 |
| **区域型供应商** | 一个供应商拆成多个区域前缀，选号只在对应区域的账号中进行 |
| **内嵌网页登录** | 登录页在 App 内打开，不跳系统浏览器；兼容旧机器的 WebView |
| **模型倍率** | 国内/国际分开维护，显示每个模型的计费倍率与能力标记 |
| **国内外智能分流** | 国内上游（腾讯/字节/讯飞/美团）强制直连，Google 按需走代理 |
| **后台保活** | 前台服务 + WakeLock/WifiLock + 系统快捷磁贴 |
| **账号管理** | 多账号轮询、额度查询、跨供应商容灾切换、批量签到、任务中心 |
| **模型测速** | 延迟三色显示（<5s 绿 / ≥5s 橙 / 超时红） |

---

## 快速开始

1. 安装 APK，打开 App，点「启动服务」；
2. 首页复制接入地址（本机 `http://127.0.0.1:8790/v1` 或局域网地址）；
3. 到「供应商」页登录各服务账号；
4. 客户端里这样配：
   - **OpenAI 协议**：Base URL = `http://<手机IP>:8790/v1`，模型填 `trae-global/glm-5.2` 或直接 `glm-5.2`
   - API Key：开启「无 Key 调用」时随意填

---

## 加入自己的上游

加一个新供应商只需三步（框架自动获得账号池、Failover、代理分流、日志、保活）：

1. `core/src/main/kotlin/dev/aigw/core/provider/<id>/XxxProvider.kt` 实现 `Provider` 接口
2. `core/.../gateway/ProviderBootstrap.kt` 加一行 `registry.register(...)`
3. `app/src/main/kotlin/dev/aigw/app/ui/providers/<id>/XxxUi.kt` 实现 `ProviderUi`，并在 `ProviderUiRegistry` 登记

**边界铁律**：Provider 的边界 = OpenAI 进、OpenAI 出，上游私有协议转换全封在 Provider 内。

两个容易漏的点：
- `ProviderUiRegistry` 忘了登记 → 打开详情页会崩
- `hosts()` 忘了写 → 代理开关对该供应商无效

---

## 构建

本仓库用 GitHub Actions 出包（`.github/workflows/android-release.yml`，`workflow_dispatch` 手动触发）。

需要 4 个仓库 secret：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 签名密钥库的 base64 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

```bash
# 触发构建
gh workflow run android-release.yml
# 或
curl -X POST -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/<owner>/<repo>/actions/workflows/android-release.yml/dispatches" \
  -d '{"ref":"main"}'
```

产物发布在 Releases，命名 `ai-gateway-<versionName>-release.apk`。

本地开发（需 JDK 17 + Android SDK）：

```bash
gradle :core:test                # 单元测试
gradle :app:assembleDebug        # debug 包（可与 release 同机共存）
gradle :cli:run --args="serve"   # 本机起网关做端到端联调
```

---

## 许可

MIT

---

## 致谢

基于 [xibai6668/Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway) 二次开发。
