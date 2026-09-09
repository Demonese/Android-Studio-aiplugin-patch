# run_shell_command 在 Windows 平台的运行机制

> 逆向对象：Android Studio 2026.1.4（build 261.26222.65.2614.16204760）`plugins/gemini/lib/aiplugin.jar`
> 中的 `com.google.aiplugin.agents.tools.execute` 包（模块 `aiplugin.agents.agents-core`），
> 以及发行版 lib/ 下的平台 jar。结论均有字节码/反编译佐证，标注「推断」的为设计推理。
> 分析日期：2026-09。

## 0. 背景

`run_shell_command` 是 coding agent（StudioBot/Gemini 插件）中**唯一**允许执行任意 shell
命令的 function tool，注册于 `ToolSetId.RUN_SHELL_TOOL`（默认 agent 启用）。

| 类 | 职责 |
|---|---|
| `RunShellCommandTool` | Tool 定义：参数 schema、ToolDescription/ToolResult、shell 参数校验 |
| `RunShellCommandArgs` | 参数：command/description/directory/shell/interactive/timeoutMillis |
| `RunShellCommandHandler`（1020 行） | 执行：审批 → 沙箱 → 命令构造 → PTY 启动 → 输出搬运 → 超时/取消 |
| `RunShellCommandToolKt`（397 行） | 工具函数：ANSI 剥离、回车折叠、退出码语义化、常量 |
| `RunShellCommandPayload`/`Result` | 轨迹/结果数据（含 TtyConnector 与 cancelAction，供 UI 交互） |

涉及的平台库：**pty4j**（`lib/intellij.libraries.pty4j.jar`，Windows 后端 ConPTY）、
**jediterm**（`intellij.libraries.jediterm.core/ui.jar`，TtyConnector/TermSize）。

## 1. 总体执行流水线（tryRun 主流程）

```
1. 运行用户命令前的 DocumentTracker 快照（context.getDocumentTracker()）
2. 权限审批   ExecutePermission.request（SharedPermissions.EXECUTE）
             → 拒绝返回 "User did not approve running that command"
3. 沙箱判定   ShellSandboxSettings.useSandbox → ShellSandboxType.wrapCommand
             （ExecutePermissionRequest.getBypassSandbox 可绕过）
4. 方言解析   ShellDialect.fromName(args.shell)（权限/展示用，非执行 gate）
5. PID 文件   createPidFile()：<project>/.idea/shell_pids*.tmp（仅非 Windows）
6. 命令构造   createProcessArgs(cli, tempFile, usePty, resolveShellPath, isWindows)
             → Pair<String[], Response>（Windows 分支硬编码 powershell/cmd）
7. 进程启动   tryRun$process$1：PtyProcessBuilder（PTY）/ ProcessBuilder（fallback）
             → process.setWinSize(WinSize(cols, rows))（PTY 初始尺寸）
8. 输出搬运   drainStream（UTF-8 读，1MB 上限截断）+ PtyRelay（LinkedBlockingQueue
             缓冲转发到 TtyConnector，供交互式 UI 显示/输入）
9. 等待循环   while (process.isAlive()) + timeoutMillis 检查
10. 收尾      getBackgroundPids(pidFile)（仅非 Windows）→ stripAnsi/collapseCarriageReturns
              → calculateExitCodeInfo → formatExitStatus → Response
```

## 2. Windows 平台执行细节

### 2.1 命令构造（createProcessArgs Windows 分支）

**shell 参数校验**：Windows 上只接受 `powershell`（默认）与 `cmd`；bash/zsh/sh/pwsh
一律在编码期报错：

```java
// 字节码等价逻辑（lbl66/lbl88 路径）
if (effectiveShell != "powershell" && effectiveShell != "cmd")
    return Response.error("Shell '" + shellArg + "' is not supported on Windows");
```

**powershell 路径（默认）**（以下为原版机制；pwsh 补丁落地后 Windows 默认/显式
powershell 由 `WindowsShellResolver` 决议为 `pwsh.exe`（探测到 PowerShell 7 时），
见第 5 节与 `docs/pwsh-support-design.md`）：

```
["powershell.exe", ("-NonInteractive" 仅当 !usePty),
 "-EncodedCommand", Base64(UTF-16LE(command))]
```

- 命令经 **UTF-16LE + Base64** 走 `-EncodedCommand`，完全规避引号/`&`/`|`/`%` 转义
- `-NonInteractive` 只加在非 PTY 分支；而 `ALWAYS_USE_PTY = true`，正常执行恒走 PTY

**cmd 路径**：

```
["cmd.exe", "/c", <command>]
```

- 检测：`CMD_WRAPPER_REGEX = ^cmd(?:\.exe)?\s+/[cK]\s+`（IGNORE_CASE）——模型显式写
  `cmd /c ...` 时即使 shell=powershell 也自动切 cmd
- 首尾成对引号（`"..."`/`'...'`）剥除后再传 `/c`
- 非交互包装：命令尾附加 ` < NUL`（stdin 重定向，防 cmd 等待键盘）；powershell 分支无此处理

**与 Unix 分支的差异**：Unix 为 `bash -l -c`（或 zsh/sh，带 `/bin/`+`/usr/bin/` 存在性探测）
+ 前缀 `export PAGER=cat; export GIT_PAGER=cat; export GIT_EDITOR=true;` + `{ cmd }
</dev/null; __code=$?; pgrep -P $$ > tmpfile; exit $__code`（后台进程组追踪）；
Windows 分支**无 PATH 探测、无后台追踪**。

### 2.2 PTY 与 ConPTY 后端

```java
if (usePty) {
    new PtyProcessBuilder().setCommand(cmdArray).setEnvironment(envMap)
        .setDirectory(workDir).start()          // com.pty4j.PtyProcess
        .setWinSize(new WinSize(cols, rows));   // 初始 PTY 尺寸（jediterm TermSize）
} else {
    new ProcessBuilder(cmdArray).directory(dir).start();   // fallback
}
```

- Windows 伪终端后端 = **Windows ConPTY**：`intellij.libraries.pty4j.jar` 内含
  `com/pty4j/windows/conpty/`（`PseudoConsole`、`WinConPtyProcess`、`WinHandleOutputStream`、
  `Pipe`）与 `com/pty4j/windows/cygwin/` 兼容层；经 JNA/JNI 调用 Win10 1809+ 的
  `CreatePseudoConsole` API
- PTY 全双工：stdout/stderr 合并（结果 `Stderr: (captured in stdout)`）；
  `interactive=true` 时用户输入经 `PtyRelay`（`LinkedBlockingQueue`+后台线程）写入
  TtyConnector，UI 通过 `RunShellCommandPayload.ttyConnector` 暴露读写与 resize
- `interactive=false` 单次执行也走 PTY（`ALWAYS_USE_PTY=true`），仅 `interactive` 决定
  是否开放输入通道

### 2.3 环境准备（Windows 特有修复）

```java
env = EnvironmentUtil.getEnvironmentMap().toMutableMap();  // IDE 进程环境快照
env["ANDROID_STUDIO_AGENT"] = "1";                         // 标记 agent 启动的子进程
if (USERPROFILE 缺失) env["USERPROFILE"] = System.getProperty("user.home");
if (HOME 缺失) env["HOME"] = System.getProperty("user.home");  // git/gpg 等依赖
// ProcessEnvironmentProvider 扩展点 populateEnvironment(env, project)
```

两个 HOME 修复是 Windows 特有的：PowerShell/cmd 子进程缺 `USERPROFILE`/`HOME` 时
git、gpg 等工具找不到用户目录。

### 2.4 输出清洗与退出码（Windows 差异明显）

**输出清洗**（`RunShellCommandToolKt`）：

- `stripAnsi`：两条正则剥离 OSC（`\u001b]...(\u0007|\u001b\\)`）与 CSI（`\u001b\[[0-9;?]*[ -/]*[@-~]`）——PowerShell 默认输出 ANSI 颜色
- `collapseCarriageReturns`：按 `\n` 分行，`\r` 取最后一段（**关键 Windows 兼容**：
  PTY 输出 `\r\n` 归一化；进度条/覆盖刷新只留最终内容）、`\b` 退格逐字符删除
- `MAX_OUTPUT_SIZE = 1MB`，超限截断加 `\n... output truncated due to size limits ...\n`

**退出码格式化**（`formatExitStatus`，Windows 分支）：JVM 收到的 32 位有符号
NTSTATUS 超出 [-1, 255] 时映射为可读信息：

| 值 | 含义 |
|---|---|
| -1073741819 (0xC0000005) | Access violation |
| -1073741510 (0xC000013A) | Ctrl+C |
| -1073741571 (0xC00000FD) | Stack overflow |
| -1073740791 (0xC0000409) | Stack buffer overrun |
| 1073807366 (0x40010006) | Ctrl+C（控制台） |
| 其他越界值 | `0x` + 大写十六进制 |

**退出码语义化**（`calculateExitCodeInfo`）：按 shell 方言解析命令首词（剥 `.exe/.sh/.bat`
后缀），同时覆盖 **Windows 原生命令**：

- `findstr`/`where`/`fc`（对应 Unix `grep`/`which`/`diff`）：code=1 → "No matches found" /
  "Differences found"
- `git` 子命令（grep/merge-base/rev-parse/diff/status）：code=128 → "Not in a git repo"，
  `--is-ancestor` code=1 → "Not an ancestor"
- `adb shell test`、`jq`、`test`/`[`：code=1 → "Condition false" 等

### 2.5 超时 / 取消 / 后台进程

- **超时**：`defaultTimeoutMillis = 600_000`（10 分钟；`Args.timeoutMillis` 可覆盖，0=永不；
  interactive 模式忽略）。超时消息形如 `<秒数> 秒后命令 ... timed out.`；清理在
  `NonCancellable + Dispatchers.IO` 上执行（tryRun$4 删除 pid 临时文件）
- **终止**：`Process.destroy()`（ConPTY 下关闭伪控制台句柄，连带进程树）；`isAlive()` 轮询确认
- **取消**：`AtomicBoolean isCancelledRef` + payload `cancelAction`（UI 停止按钮触发），
  `CancellationException` 后状态置 `Interrupted`
- **后台进程**：**仅 Unix 支持**（`createPidFile` + `pgrep -P $$` 收集到
  `<project>/.idea/shell_pids*.tmp`）；Windows 恒为 `(none)`，工具文档要求用
  `Start-Process`/`Start-Job` 自行处理后台

### 2.6 权限与沙箱的 Windows 差异

- **审批**：`ExecutePermission.request`（`SharedPermissions.EXECUTE`），拒绝返回
  `"User did not approve running that command"`；请求带 `bypassSandbox`
- **沙箱**：`ShellSandboxType` 枚举只有 `MACOS / DOCKER / SANDBOX_RUNTIME / BUBBLEWRAP /
  CUSTOM`——**Windows 没有原生沙箱类型**，本机执行不做沙箱包装；DOCKER 类型带
  `toDockerPath(forceWindows)`（`C:\...` → `/c/...`）用于 Linux 主机上的 Windows 路径转换
- 工作目录：`PathUtilsKt.resolveWorkDir(args.directory, ...)` + `SessionStorage.getRoot`；
  沙箱路径限制在 `$SANDBOX_ROOT`（`VfsUtilCore.isAncestor` 校验），Windows 无沙箱时退回项目根

## 3. 为什么默认 PowerShell、且只允许 powershell/cmd

### 3.1 代码事实

1. **`ShellDialect` 只有 3 种方言**（`agentsdk/permissions/ShellDialect.java`）：
   `SH / CMD / POWER_SHELL`。`fromName()`：`"" → default()`、`"powershell"/"pwsh" →
   POWER_SHELL`、`"cmd" → CMD`、其余任意值 → `SH`。`default() = isWindows ? POWER_SHELL : SH`
2. **工具描述写死**：`getToolDescription()` 即 `"Executes a shell command in PowerShell
   (the default) or cmd on Windows"`；`getToolArgument("shell")` 告知模型
   `"Shell name -- either "powershell" (default) or "cmd""`
3. **执行层硬 gate**：createProcessArgs 中 effectiveShell 仅 `powershell`/`cmd` 两个值
   进入编码分支，其余直接返回 `"Shell '<x>' is not supported on Windows"`；命令数组
   硬编码 `powershell.exe`/`cmd.exe`，无 PATH 探测（Unix 分支才有 `/bin/`、`/usr/bin/` 探测）

### 3.2 值得注意的内部不一致

权限解析层 `ShellDialect.fromName("pwsh") → POWER_SHELL`（能通过命令语法解析与审批），
但执行层 `"pwsh" != "powershell"` → **必然报错**。即 `shell="pwsh"`（PowerShell 7）
能过权限检查却执行必失败——执行层只认 `powershell.exe` 一个实现。这是设计缺陷或
有意为之（提示模型改用 powershell），现状如此。

### 3.3 设计推理（推断）

1. **存在性保证**：`powershell.exe`（Windows PowerShell 5.1）与 `cmd.exe` 由 Windows
   10+ 保证存在；`pwsh` 需单独安装，bash 在 Windows 上没有确定位置（Git\bin\bash.exe、
   Git\usr\bin\bash.exe、WSL bash.exe、MSYS2 各不相同），路径探测 + MSYS2 路径转换
   （`C:\`→`/c/`）的失败面大——`toDockerPath` 仅在 DOCKER 沙箱的受控场景处理此事
2. **转义策略依赖 PowerShell**：`-EncodedCommand`（UTF-16LE Base64）彻底规避转义；
   cmd 用引号剥离 + ` < NUL`；bash 没有同等可靠的包装方案
3. **ConPTY 兼容性**：MSYS2/Cygwin bash 在 ConPTY 下有已知 PTY 交互问题（cygwin 自模拟
   PTY 与 ConPTY 冲突），支持它意味着引入整套兼容层
4. **语义层支持面最小化**：`calculateExitCodeInfo` 只需覆盖 3 方言 + NTSTATUS 映射
5. **与平台惯例一致**：JetBrains 平台在 Windows 的终端默认 shell 同样是 PowerShell
   （见下一节），保持下游一致性

## 4. aiplugin 之外的写死逻辑（平台层）

对发行版全部 379 个 jar 扫描验证：

| 位置 | 写死内容 | 证据 |
|---|---|---|
| **pty4j**（`lib/intellij.libraries.pty4j.jar`） | Windows 后端固定 ConPTY：`com/pty4j/windows/conpty/PseudoConsole`、`WinConPtyProcess`、`WinHandleOutputStream`；另有 cygwin 兼容层 `windows/cygwin/` | jar 类清单 |
| **jediterm typeahead**（`intellij.libraries.jediterm.core.jar`） | `TypeAheadTerminalModel$ShellType` 枚举**只认 `Bash`/`Zsh`/`Unknown`**——PowerShell/cmd 无打字预测适配（fallback Unknown） | 枚举字节码 |
| **aiplugin 内其他组件** | `ShellUtils`：Windows 可执行扩展名写死 `{.com, .exe, .bat, .cmd, .vbs, .vbe, .js, .jse, .wsf, .wsh, .msc}`（PATHEXT 语义）；`ShellSandboxType` 无 Windows 类型 → Windows 上沙箱禁用 | 反编译 |
| **平台终端插件**（`com.intellij.terminal`） | 本发行版仅在 `intellij.platform.lang.impl.jar` 打包 74 个 UI 类（JBTerminalPanel/TerminalSession/TerminalUiSettingsManager 等），**无 shell 启动器类**（`LocalTerminalDirectRunner`、`ShellStartupOptions` 不存在于任何 jar）——终端默认 shell 的启动选择逻辑在此发行版中无法从字节码验证 | jar 扫描 |

结论：默认 PowerShell 不是 aiplugin 的独有偏好，而是整条链路的平台共识——微软自带、
pty4j ConPTY 后端、PowerShell 转义方案（EncodedCommand）均围绕它设计；bash 在 Windows
上因路径/PTY/转换问题被整条链路刻意排除。aiplugin 内唯一的口径松动是权限层认识
`pwsh` 而执行层不认识（执行层是最终裁决者）。

## 5. 相关补丁（已落地，见 docs/pwsh-support-design.md）

- **pwsh 优先支持**：Windows 上 `where.exe` 探测 PowerShell 7，可用则默认/显式
  powershell 均走 `pwsh.exe -EncodedCommand`（行为层），工具描述同步标明
  "PowerShell 7 (the default)"（文案层）。实现：新增 `WindowsShellResolver` +
  ASM 注入 `RunShellCommandHandler.createProcessArgs`（3 处）与
  `RunShellCommandTool.getToolDescription`（2 处）。
- 若未来要在 Windows 上放宽 shell 选择（如 git-bash），需同时处理：shell 路径探测、
  MSYS2 路径转换、ConPTY 兼容性、`ShellDialect` 扩展、退出码语义表——工程量集中在
  `RunShellCommandHandler` 与 `RunShellCommandToolKt`，全部为插件内字节码可改点
  （工具类为新增源码友好面），不涉及平台 jar。