# 必须保留的启动约束（最高优先级）

- 软件性质及使用责任声明是使用软件的前置条件，禁止删除、跳过或降级为可选提示。
- 首次安装和从无声明的旧版升级均必须确认。确认记录独立于版本号、教程进度和旧版偏好；禁止迁移时默认同意。
- 原文见 `UsageConsent.statement`。显示满 5 秒后才允许明确点击「我已阅读并确认」；未确认退出不保存同意，下次仍须显示。切到后台或重建后重新计时。
- 确认前不得加载功能界面、处理外部文件导入、恢复教程练习、启动悬浮窗或执行无障碍截图。开机恢复与服务重启也不得绕过。
- 修改启动、导航、导入、服务、教程或持久化时必须保持此约束。相关回归测试为 `UsageConsentInstrumentedTest`；发布前必须运行，不能把跳过算通过。
- 声明修订需要重新确认时递增 `UsageConsent.REVISION`；普通升级保留已明确确认的当前声明记录。

# 对齐功能的诊断约束

- 新增、调整或优化对齐功能（含算法、参数、门控、调用链和渲染）时，必须同时评估日志是否仍能达到“可还原、可重建、可追溯”的目标。如果达不到，必须在同一次修改中升级日志记录，不能只改功能而留下诊断缺口。
- 不得只记录成功或失败。必须保留各阶段实际使用的参数、缩放与位移、候选和中间数据、门控实测值及阈值、判定结果与拒绝原因，并能关联到同一次对齐请求。
- 耗时是对齐的关键指标。截图、排队、算法各环节、绘制提交及总耗时必须可查；区分嵌套耗时和独立阶段耗时，以及算法成本和诊断记录成本，不能用一个总耗时掩盖瓶颈。
- 诊断包应能关联原始输入、实际参考数据、运行配置、算法/构建版本和源码指纹，并提供公用结果、日志扩展和回放测试接口。输入缺失、记录关闭、取消、错误和不可回放必须明确标记，不能把缺少证据当作验证通过。
- 修改诊断格式、算法输入或中间数据后，应验证记录与回放链路仍可用；必要时同步更新诊断格式及其读取器。
- 取消贴合必须停止旧请求的实际计算，不能只递增代次或丢弃回调。算法扩展必须在有界循环和原生调用之间响应取消；日志记录取消请求、实际退出阶段及响应耗时，测试覆盖关闭 👁 后退出和再次显示时的新任务。
- 可见、未知/迷雾、遮挡与屏幕外数据必须区分，不能将未知参考结构作为缺失证据。缩放搜索域、候选位姿的区别判定域及中间掩码必须可还原；真实失败修复应回放对应版本的原始输入，不能用合成测试通过替代。

# 照片与相册污染风险（优先审查）

- 任何图片、截图、诊断、测试数据或验证脚本的改动，优先审查是否可能进入用户照片或相册。
- 运行时图片与诊断只使用应用内部 `filesDir` / `cacheDir`；测试证据优先使用测试 APK 的内部目录。禁止自动写入共享媒体目录、外部存储或调用媒体扫描、相册发布接口。
- ADB 截图使用 `tools/Save-DeviceEvidence.ps1` 直接输出到主机 `.verify`；UI 层级临时文件仅用 `/data/local/tmp`。不得在设备共享存储暂存 PNG 后再拉取。
- 执行图片相关验证前运行 `tools/Test-MediaStorageBoundary.ps1`；必须包含被 Git 忽略的本地验证脚本。Android `preBuild` 也必须执行同一组边界规则，命中风险时先处理再构建。
- 使用设备上已安装的测试 APK 前，运行 `tools/Assert-PrivateTestEvidenceApk.ps1` 核对私有证据存储标记；旧 APK 或无法核对时禁止继续执行截图测试。标记不能替代当前源码和构建边界检查。
- 用户主动选择的诊断 ZIP 导出保持明确点击与文件选择流程；不得将其改成自动图片导出。发现历史相册条目时只读核查，删除或迁移前须获得用户对具体文件的授权。
- 不在用户正在使用的设备或模拟器上执行可能卸载、清数据或重建数据目录的测试；这类测试使用隔离测试环境。上述要求不改变必须保留的启动声明约束。

# 编译环境与完成交付（必须执行）

- 本机已核对的环境（2026-10-01）：Windows / PowerShell；`JAVA_HOME=D:\Android\jbr`（OpenJDK/JBR 25.0.3）；`GRADLE_USER_HOME=C:\Users\m'r\.gradle`；项目 Gradle Wrapper 为 9.3.1，启动 JVM 使用上述 JBR，Daemon 按 `gradle/gradle-daemon-jvm.properties` 使用 Java 21（本机缓存：`C:\Users\m'r\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`）；Android SDK 为 `D:\anzhuo`（`local.properties` 的 `sdk.dir`），已安装 `platforms\android-36.1` 和 `build-tools\36.0.0`。
- 每次完成本项目工作后，必须针对最终工作区完成 APK 构建并交付产物；仅编译 Kotlin、构建测试 APK、静态检查或引用旧 APK 均不能代替。默认执行 `:app:assembleDebug`；用户指定其他构建类型时按指定类型执行。
- 在项目根目录使用以下 PowerShell 命令。环境变量仅设置在当前进程，不修改机器全局配置；保留已有 Gradle 缓存和构建编号：

```powershell
$env:JAVA_HOME = 'D:\Android\jbr'
$env:GRADLE_USER_HOME = "C:\Users\m'r\.gradle"
& .\tools\Test-MediaStorageBoundary.ps1
if (-not $?) { throw 'Media storage boundary failed' }
& .\gradlew.bat :app:assembleDebug --offline --project-cache-dir .verify\gradle-project-cache
if ($LASTEXITCODE -ne 0) { throw 'Debug APK build failed' }
Get-Content -LiteralPath .\app\build\outputs\apk\debug\output-metadata.json
Get-ChildItem -LiteralPath .\app\build\outputs\apk\debug -Filter *.apk |
    Select-Object FullName, Length, LastWriteTime
```

- 默认 APK 输出目录：`D:\!Playground\IDVB_Android\app\build\outputs\apk\debug\`。APK 文件名包含产品版本及每次生成的构建编号，必须读取本次 `output-metadata.json` 确认实际文件名、版本和文件存在，最终回复给出本次构建结果及 APK 的完整绝对路径（可点击链接），不能只给目录或预估文件名。
- 缓存目录需要写权限；沙箱阻止写入用户 Gradle 缓存时走工具权限申请，不删除缓存、不改 ACL。离线缺依赖时查明原因并按现有网络权限补齐；环境路径变化时重新核对可用环境，禁止把失败或跳过算作构建成功。
- 本机验证中曾遇到 `:app:mergeDebugAssets` 对同一路径的生成诊断资产报 `Duplicate resources`；可先在上述命令中增加 `--rerun-tasks` 强制重跑，检查是否为增量合并状态问题，禁止直接删除诊断资产或跳过来源校验。若仍失败，继续定位原因。
- 构建失败必须排查并修复后重试；若受外部条件阻塞，明确报告阻塞原因和未生成可交付产物，不宣称已完成。不以构建成功代替实际设备交互验证；发布前仍必须运行 `UsageConsentInstrumentedTest`，跳过不能算通过。

# 禁止 Agent 擅自删除 App（必须遵守）

- 未经用户明确授权具体设备及具体 App，Agent 禁止卸载、删除 App（包括本项目主 App、测试 App 和其他 App），禁止执行 `adb uninstall`、`pm uninstall`、界面卸载或任何等效操作。
- 禁止为安装 APK、解决签名/版本冲突、重新测试或恢复环境而擅自卸载重装、清除应用数据（如 `pm clear`）或删除应用数据目录。遇到此类冲突先保留现有 App 与数据，报告原因并获取具体授权。
- 运行设备测试前必须检查脚本、Gradle 任务及测试工具是否会自动卸载、清数据或重建应用数据目录；有此行为时不得直接执行。隔离测试环境也不自动获得删除 App 的授权。
- 构建与交付 APK 不包含自动安装或卸载设备上的 App；用户明确要求安装时，也必须遵守以上卸载和数据保护约束。
