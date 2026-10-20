# 本机 Ollama / Qwen3 8B 配置

## 安装位置

| 项目 | 路径 |
| --- | --- |
| Ollama 程序 | `D:\Ollama` |
| 模型目录 | `D:\Ollama\models`（用户环境变量 `OLLAMA_MODELS`） |
| 服务地址 | `http://127.0.0.1:11434` |
| 模型 | `qwen3:8b` |

安装包下载到 `D:\ollama\OllamaSetup.exe`，安装日志在 `D:\ollama\install.log`。

## 启动与拉取

```powershell
# 新开终端后 OLLAMA_MODELS 已由 setx 写入用户环境变量
D:\Ollama\ollama.exe serve
D:\Ollama\ollama.exe pull qwen3:8b
D:\Ollama\ollama.exe list
```

> 如果托盘程序先启动，请确认它是设置 `OLLAMA_MODELS` 之后启动的；否则旧进程可能仍把模型
> 下载到 `C:\Users\<用户>\.ollama\models`。本机当前由 `D:\Ollama\ollama.exe serve` 以
> `OLLAMA_MODELS=D:\Ollama\models` 启动。

## 后端配置

`backend/src/main/resources/application-ollama.yml` 已提供本地模型配置：

```yaml
app:
  ai:
    provider: ollama
    # 原生 /api/chat：Qwen3 的 OpenAI 兼容 /v1 端点会把 token 花在 reasoning 上，
    # 可能返回空 content；原生端点支持 think:false + format:json。
    endpoint: http://127.0.0.1:11434/api/chat
    model: qwen3:8b
    api-key: ollama
    timeout-seconds: 300
```

启动后端时激活 profile：

```powershell
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=ollama
```

或使用环境变量（不激活 profile 也可以）：

```powershell
$env:AI_PROVIDER = "ollama"
$env:AI_MODEL = "qwen3:8b"
$env:AI_TIMEOUT_SECONDS = "300"
```

默认（不配置 `AI_PROVIDER`）仍走 DeepSeek，`DEEPSEEK_API_KEY`/`DEEPSEEK_MODEL` 行为不变。

## 验证

```powershell
# Ollama 自身
curl http://127.0.0.1:11434/api/version

# 原生 /api/chat（后端使用的路径）
curl http://127.0.0.1:11434/api/chat `
  -H "Content-Type: application/json" `
  -d '{"model":"qwen3:8b","stream":false,"think":false,"format":"json","options":{"num_predict":128},"messages":[{"role":"user","content":"请输出 {\"ok\":true}"}]}'

# 后端客户端真实调用（opt-in）
$env:OLLAMA_LIVE_TEST = "1"
mvn -B -ntp -f backend/pom.xml -Dtest=OllamaLiveClientTest test
```

`OllamaLiveClientTest` 在普通 `mvn test` 中会跳过，只有显式设置 `OLLAMA_LIVE_TEST=1` 才调用
本机模型；它不是生产 AI 质量验收，真实公告解析与助手回答仍需人工抽查。
