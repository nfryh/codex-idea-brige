<!-- 2026-09-30：项目自有本地桥接接口和模型上下文协议工具合同。 -->
# 本地桥接协议 1

2026-10-05 修改：取消重复的项目授权，保留项目、终端和会话隔离；提交时补注册漏收启动事件的会话，安全磁盘读取交由插件管理的普通 Java 进程执行。地址及入参、出参均未变化；`403` 表示联动已关闭或项目不可用，不再表示缺少插件项目授权。

2026-10-06 修改：`POST /v1/hooks` 的提交处理会检查插件插入的草稿引用标记，删除标记后不再附加对应冻结快照。地址、入参和出参字段不变，仍使用原生 `prompt` 字段检查。自动编辑器缓存增加 `selectionLineCount`，表示真实选区覆盖的不重复行数；附近代码不计入选区。这也影响 `POST /v1/tools/call` 中 `ide_get_context` 的结果元信息，没有新增地址或工具参数。

2026-10-06 原生运行修正：`POST /v1/hooks` 在实际收到 `SessionStart` 或首条 `UserPromptSubmit` 后，仅关联同一个认证终端已经收到的草稿引用。实例、项目、终端身份仍来自原有认证头；不会按工作目录寻找窗口，不自动消费没有终端目标的本地引用。地址、入参、出参和原生回调命令均不变。当前项目根目录始终包含在文件范围内，项目内模块不另建能够绕开敏感目录限制的更深范围。

仅监听 `http://127.0.0.1:<系统分配的随机端口>`。本地接口使用 Hypertext Transfer Protocol（超文本传输协议，HTTP）；这是进程间通信，不是对外部署的后端，也不是模型上下文协议 HTTP 服务器。

每次请求必须为 `POST`，使用 `Content-Type: application/json; charset=utf-8` 和明确的 `Content-Length`。拒绝分块传输、浏览器 `Origin`、查询参数、重复绑定头部和非精确 Host。响应后关闭连接。

认证头为 `Authorization: Bearer <终端专用秘密>`；同时验证 `X-ICB-Protocol: 1`、`X-ICB-Instance-Id`、`X-ICB-Project-Id`、`X-ICB-Terminal-Id`。秘密只从受限终端描述文件取得，不放在提示内容、参数或查询地址中。

输入上限 1 MiB、输出上限 64 KiB；从接受连接开始计算 900 毫秒期限，包含排队、请求头和正文读取及响应写入。8 个工作线程，32 个等待位置。客户端连接上限 150 毫秒、读取上限 600 毫秒、主转发上限 1200 毫秒、交接确认另限 200 毫秒。

|地址|入参|出参|作用|
|---|---|---|---|
|`POST /v1/hooks`|原生事件对象：`hook_event_name`、`session_id`、`cwd`；提交另须 `turn_id`、`prompt`；结束或中断须 `turn_id`|Codex 原生 Hook JSON；提交带 `X-ICB-Batch-Id`|注册、冻结本轮引用、附加缓存、记录结束或清理会话|
|`POST /v1/hooks/ack`|空正文，头部 `X-ICB-Batch-Id`|`204`，无正文|确认当前终端某批次已写入标准输出|
|`POST /v1/connections/open`|`clientVersion: "1.0.0"`、`protocolVersion`|`connectionId`、`capabilities: ["tools"]`|为同一终端凭证绑定工具连接|
|`POST /v1/connections/close`|`connectionId`|`204`，无正文|清理该工具连接|
|`POST /v1/tools/call`|`name`、`arguments`、`connectionId`|`content: [{type: "text", text: ...}]`、`isError`|执行当前项目内的受限工具|
|`POST /v1/health`|`{}`|`protocol: 1`、`version: "1.0.0"`、`state: "RUNNING"`|认证后的服务状态检查，不列项目或秘密|

`protocolVersion` 允许 `2025-11-25`、`2025-06-18`、`2024-11-05`。其他值拒绝，不声明任务、采样或其他未实现能力。

成功传输为 `200` 或 `204`；非法协议为 `400`，认证失败为 `401`，联动已关闭或项目在处理期间关闭为 `403`，绑定冲突为 `409`，超量为 `413`，过载为 `429`。提交业务拒绝使用 HTTP `200` 和 `{"decision":"block","reason":"..."}`；转发器不把内部错误对象直接当 Hook 输出。

本轮影响 `POST /v1/hooks` 和 `POST /v1/tools/call` 的内部处理，地址、入参和出参字段均不改变。没有新增对外地址。本地读取进程仅使用父子进程的标准输入输出，不开放监听端口。

## 模型上下文协议工具

对 Codex 提供换行分帧、UTF-8 的 JSON-RPC（使用 JSON 结构的远程过程调用协议），经过标准输入和标准输出，不使用 `Content-Length` 分帧。实现 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call` 和取消通知。通知不产生响应，非法消息和业务拒绝分别处理。

|工具|入参|结果与限制|
|---|---|---|
|`ide_get_context`|可选 `view: "active"`|当前项目的元信息及有界缓存选区；未授权未保存内容时去除正文；不可用时说明状态和根标识|
|`ide_read_document`|`rootId`、`relativePath`；可选 `startLine`、`endLine`|正文、`source`、修改版本或摘要、采集时间、未保存状态；未授权未保存读取时使用磁盘版；返回正文最多 24 KiB|
|`ide_get_diagnostics`|`rootId`、`relativePath`|分析状态、文档版本、采集时间、最多 100 条现有诊断|
|`ide_open_file`|`rootId`、`relativePath`、`line`、`column`|验证后返回已调度状态；行列从 1 开始，列按 UTF-16（Java 文本使用的 16 位编码单元）定义，越界拒绝|
|`ide_show_diff`|服务已有的 `reviewId`|验证后调度原生只读比较；不接受补丁，不应用文件修改|

工具参数不接受 `projectId`、`sessionId` 或其他未知字段，不允许借参数切换绑定。只读工具执行失败使用 `isError: true`；显示文件和比较会影响界面，未标成完全无副作用。

## 冻结引用

结构见 [context-attachment.schema.json](context-attachment.schema.json)。`PATH_ONLY` 表示只附加相对路径；`SELECTION_SNAPSHOT` 表示真实选区；`FILE_SNAPSHOT` 表示当前文档全文。没有正文时坐标为 `null`。

所有偏移用 UTF-16 编码单元，范围不包含尾端。行号从 1 开始，结束行按最后选中字符计算。多选区分别保存，不合并中间代码。自动选区去掉与显式引用完全相同的片段。

队列按项目、终端、会话联合隔离。相同轮次的关键事件语义完全一致时返回同一冻结结果，语义变化时拒绝；缺少轮次标识不消费队列。交接确认失败不会再输出第二个 JSON，也不会自动重新发送给其他会话。
