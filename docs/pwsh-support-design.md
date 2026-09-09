# 补丁设计：动态检测 pwsh（PowerShell 7）并优先使用

> 配套分析：`docs/powershell.md`（run_shell_command 在 Windows 的运行机制）。
> 目标版本：Android Studio 2026.1.4，aiplugin.jar。本文给出可落地的字节码补丁方案：
> 新增 `WindowsShellResolver` + ASM 修改 `RunShellCommandHandler.createProcessArgs`
> 三处注入点，使 Windows 平台**检测到 pwsh 时优先用 pwsh.exe，未检测到保持
> powershell.exe 现状**。

## 1. 现状（三个写死点，均位于 `createProcessArgs`）

| # | 位置（字节码偏移） | 现状 | 问题 |
|---|---|---|---|
| A | `ldc "powershell"`（137，isWindows 默认分支） | 默认 shell 恒为 `powershell` | 无 pwsh 检测 |
| B | `astore 8`（154，shellArg 定稿于 slot 8） | 无归一化 | `pwsh` 会在 redirect（171 处 `< /dev/null` 分支）与 effectiveShell 判定（382/422 处）全部失配，最终报 "not supported on Windows" |
| C | `ldc "powershell.exe"`（440，powershell 编码分支） | 硬编码 exe 名 | 无法改用 pwsh.exe |

现状还有两个知识层事实：
- `ShellDialect.fromName`（agentsdk/permissions）已认识 `pwsh → POWER_SHELL`、空串 → 平台默认（Windows 为 POWER_SHELL）——**权限/命令解析层无需任何改动**
- `calculateExitCodeInfo(shell, ...)` 按 payload 的 shell 原文解析方言：pwsh 与 powershell 同属 POWER_SHELL 语义，**语义层无需改动**

## 2. 总体方案：归一化 + 探测决议

```
shellArg 定稿后立即归一化：  "pwsh" → "powershell"（其余原样）
  → redirect 分支（< NUL 逻辑）、effectiveShell 判定、错误信息全部复用现有 powershell/cmd 逻辑

默认 shell 决议：            resolver.defaultShellName() → "pwsh" 或 "powershell"
  → 归一化后进入 powershell 分支，编码方式（-EncodedCommand / -NonInteractive）与 5.1 完全一致

可执行文件决议：            resolver.executable() → "pwsh.exe" 或 "powershell.exe"
  → 只换宿主进程名，参数序列不变（pwsh 完整支持 -EncodedCommand / -NonInteractive）
```

优点：
- **零新分支、零新栈帧**（B 注入是无分支直线代码；A/C 是 LDC 指令替换）——项目
  COMPUTE_MAXS 下无需补 F_SAME，与既有补丁风格一致
- 报错路径不变：`shell="bash"` 等仍走 `"Shell '<x>' is not supported on Windows"`
- 无 pwsh 的机器行为与现在**完全一致**（回退 powershell.exe）

## 3. 新增类：WindowsShellResolver（探测以 where.exe 为准）

位置：`src/main/java/com/google/aiplugin/agents/tools/execute/WindowsShellResolver.java`
（独立无依赖，`--release 21` 编译，进 jar 组装列表）

### 3.1 探测策略（v2：`where.exe` 为主）

`where.exe` 是 Windows 自带的 PATH 全量搜索工具，按 PATHEXT 追加扩展名、输出**所有**
匹配路径（示例）：

```
where.exe pwsh      → C:\Program Files\PowerShell\7\pwsh.exe（以及 PATH 中其他匹配）
where.exe pwsh.exe  → 同上（显式扩展名，避免 PATHEXT 误匹配，推荐）
```

因此以 `where.exe pwsh.exe` 为**主探测手段**：

| 层级 | 手段 | 成本 | 说明 |
|---|---|---|---|
| 1 | `where.exe pwsh.exe`，2s 超时，解析所有输出行 | ~100ms（一次性，之后缓存） | 输出按 PATH 顺序列出全部匹配；**剔除 `WindowsApps` 别名行**后非空 ⇒ pwsh 真实可用 |
| 2 | 仅当 where 只命中 WindowsApps 别名（商店安装场景）| 再执行一次 `pwsh.exe -NoProfile -NonInteractive -Command "$true"`（3s 超时，退出码 0）| 未安装的别名是 0 字节 app-execution-alias shim，启动会被 Windows 拦截弹商店；**执行验证**是唯一能区分"商店已装/未装"的手段 |
| 3 | 以上均失败/异常/超时 | 0 | 回退 `powershell.exe`（现状），不抛异常 |

为什么不用固定路径探测当主力：`%ProgramFiles%\PowerShell\7\pwsh.exe` 只覆盖标准安装，
用户可能装到任意盘符/目录（如 `D:\...\pwsh.exe`）；而 `where.exe` 忠实反映进程
PATH，与后续 `PtyProcessBuilder` 实际启用的解析结果一致，探测即执行、零猜测。
固定路径检查可保留为探测前 fast-path（命中即 true，省一次子进程），但**不作为**
判定依据（仅加速）。

WindowsApps 别名过滤要点：别名文件**未安装时也存在于**
`%LOCALAPPDATA%\Microsoft\WindowsApps\`（0 字节 appref shim），`where.exe` 会列它，
因此：
- `where.exe` 输出含非 WindowsApps 路径 → 直接判定可用（无需执行验证）
- 输出**仅** WindowsApps → 走层级 2 执行验证（覆盖"仅商店安装 pwsh"的用户）
- 层级 2 验证失败（弹商店/退出码非 0）→ false

### 3.2 源码草案

```java
package com.google.aiplugin.agents.tools.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows 平台的 PowerShell 宿主探测：优先 PowerShell 7 (pwsh)，
 * 未安装时回退 Windows PowerShell 5.1 (powershell.exe)。
 * 探测结果进程级缓存（安装/卸载 pwsh 需重启 IDE 生效）。
 * 仅 Windows 使用；非 Windows 恒返回 powershell 家族现状。
 * 探测主手段：where.exe（PATH 全量搜索，与 PtyProcessBuilder 实际解析一致）。
 */
public final class WindowsShellResolver {
    /** 加速用 fast-path（命中即 true，省一次子进程；不作为判定依据） */
    private static final String[] FAST_PATHS = {
        "%ProgramFiles%\\PowerShell\\7\\pwsh.exe",
        "%ProgramFiles(x86)%\\PowerShell\\7\\pwsh.exe",
    };

    private static volatile boolean probed = false;
    private static volatile boolean pwshAvailable = false;

    private WindowsShellResolver() {
    }

    /** 默认 shell 名：探测到 pwsh 返回 "pwsh"，否则 "powershell"（原默认）。 */
    @NotNull
    public static String defaultShellName() {
        return isPwshAvailable() ? "pwsh" : "powershell";
    }

    /** 实际可执行文件：探测到 pwsh 返回 "pwsh.exe"，否则 "powershell.exe"。 */
    @NotNull
    public static String executable() {
        return isPwshAvailable() ? "pwsh.exe" : "powershell.exe";
    }

    /** shell 名归一化："pwsh"/"PWSH" → "powershell"，其余原样返回。
     *  幂等；用于让下游 powershell/cmd 分支（redirect、effectiveShell、错误信息）复用。 */
    @NotNull
    public static String normalizeShell(@NotNull String name) {
        if ("pwsh".equalsIgnoreCase(name)) {
            return "powershell";
        }
        return name;
    }

    /** 探测 pwsh 是否可用（首次调用后缓存；非 Windows 恒 false）。 */
    public static boolean isPwshAvailable() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        if (probed) {
            return pwshAvailable;
        }
        synchronized (WindowsShellResolver.class) {
            if (probed) {
                return pwshAvailable;
            }
            pwshAvailable = probe();
            probed = true;
            return pwshAvailable;
        }
    }

    private static boolean probe() {
        for (String pattern : FAST_PATHS) {               // fast-path：标准安装直接命中
            String path = expandEnv(pattern);
            if (path != null && new File(path).isFile()) {
                return true;
            }
        }
        java.util.List<String> hits = where("pwsh.exe");  // 主探测：PATH 全量搜索
        java.util.List<String> real = filterWindowsApps(hits);
        if (!real.isEmpty()) {
            return true;                                   // 真实安装（非别名）存在
        }
        if (!hits.isEmpty()) {
            // 仅命中 WindowsApps 别名（商店安装场景）：执行验证区分已装/未装 shim
            return verifyExecutable(hits.get(0));
        }
        return false;
    }

    /** 执行 where.exe 全量搜索（2s 超时），返回所有匹配绝对路径（按 PATH 顺序）。 */
    static java.util.List<String> where(String exeName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Process p = new ProcessBuilder("where.exe", exeName)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            String text = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && new File(t).isFile()) {
                    out.add(t);
                }
            }
        } catch (Exception ignored) {
            // 探测失败一律视为无 pwsh，回退 powershell.exe
        }
        return out;
    }

    private static java.util.List<String> filterWindowsApps(java.util.List<String> hits) {
        java.util.List<String> real = new java.util.ArrayList<>();
        for (String h : hits) {
            if (!h.contains("WindowsApps")) {
                real.add(h);
            }
        }
        return real;
    }

    /** 对候选执行 pwsh 本体验证（3s 超时，退出码 0 即真可用）。
     *  仅用于唯一候选是 WindowsApps 别名时。 */
    private static boolean verifyExecutable(String pwshPath) {
        try {
            Process p = new ProcessBuilder(pwshPath,
                    "-NoProfile", "-NonInteractive", "-Command", "$true")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String expandEnv(String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("%([A-Za-z0-9()]+)%").matcher(pattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    env != null ? env : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 测试注入：null 复位为"未探测"，Boolean 直接覆写结果。 */
    @VisibleForTesting
    public static void setForTesting(Boolean available) {
        if (available == null) {
            probed = false;
            pwshAvailable = false;
        } else {
            probed = true;
            pwshAvailable = available;
        }
    }
}
```

要点：
- `defaultShellName()` 返回 `"pwsh"` → 归一化后仍走 powershell 分支，**归一化与可执行名决议解耦**，任意组合自洽
- 探测失败/异常一律回退 `powershell.exe`（现状），不向执行路径抛异常
- `where.exe` 结果逐行做 `new File(t).isFile()` 复用过滤（where 只输出绝对路径，但防御性校验不增加成本）；`filterWindowsApps` 剔除别名 shim
- 商店安装场景只有 `pwsh.exe -Command "$true"` 执行验证能区分（未装别名启动即弹商店/退非 0）

## 4. ASM 补丁：patchRunShellHandler（PatchTool 新命令 `winshell`）

类：`com/google/aiplugin/agents/tools/execute/RunShellCommandHandler`
方法：`createProcessArgs$aiplugin_agents_agents_core`
（注意是 Kotlin 内部名，收尾带 `$aiplugin_agents_agents_core`；已有 `@VisibleForTesting public`）

新常量：`SHELL_RESOLVER = "com/google/aiplugin/agents/tools/execute/WindowsShellResolver"`

### 注入点 A —— 默认 shell（偏移 137）

定位特征：`LDC "powershell"` 且前驱为 `IFEQ`（紧随 `ILOAD 5`，即 isWindows 参数）、后继为 `GOTO`（方法内唯一的 `ldc powershell; goto` 紧邻结构）。

```
原文: ldc_w  "powershell"
改为: invokestatic WindowsShellResolver.defaultShellName:()Ljava/lang/String;
```

### 注入点 B —— shellArg 归一化（偏移 154 之后）

定位特征：注入点 A 之后方法体内第一个 `ASTORE 8`（`getShell → checkcast → astore 8` 序列；slot 8 即 shellArg，其后所有使用点都从 slot 8 读）。

```
插入于 astore 8 之后：
  aload 8
  invokestatic WindowsShellResolver.normalizeShell:(Ljava/lang/String;)Ljava/lang/String;
  astore 8
```

无分支、无新帧。一次插入覆盖 redirect 分支（偏移 171 的 `< /dev/null` 判定）、
effectiveShell 判定（382/422）、isCmdWrapper 分支（382 前）、错误消息 —— 全部复用。

### 注入点 C —— 可执行文件（偏移 440）

定位特征：方法内唯一 `LDC "powershell.exe"`（数组元素，`iconst_1; anewarray; dup;
iconst_0; ldc ...; aastore` 结构）。

```
原文: ldc_w  "powershell.exe"
改为: invokestatic WindowsShellResolver.executable:()Ljava/lang/String;
```

### 补丁后行为矩阵

| 环境 | 默认（shell 缺省） | 显式 shell="powershell" | 显式 shell="pwsh" | 显式 shell="cmd" | 其他 |
|---|---|---|---|---|---|
| 有 pwsh | `pwsh.exe -EncodedCommand ...` | `pwsh.exe`（pwsh 优先策略） | 归一化→`pwsh.exe` 同左 | `cmd.exe /c` 不变 | 仍报 not supported |
| 无 pwsh | `powershell.exe -EncodedCommand ...`（**与现状完全一致**） | `powershell.exe` | 归一化→`powershell.exe`（比现状改善：不再报错） | 不变 | 不变 |

设计取舍：显式 `shell="powershell"` 时仍优先 pwsh（PowerShell 语义兼容，5.1/7 对
`-EncodedCommand`/`-NonInteractive` 行为一致）；如未来需要"显式 5.1"可再引入开关。

## 5. 文案层更新（已实现，提交 0bcc5ed）

`RunShellCommandTool` 的 ToolDescription/参数文案随 pwsh 支持更新：

- summary（isWindows 分支 LDC → `WindowsShellResolver.summaryForWindows()`）：
  pwsh 可用 → `"Executes a shell command in PowerShell 7 (the default) or cmd on Windows"`；
  否则保持原版 `"...PowerShell (the default) or cmd on Windows"`
- description（isWindows 分支 LDC → `WindowsShellResolver.descriptionForWindows()`）：
  pwsh 可用 → `"Executes as \`pwsh.exe -Command <command>\`. Supports background processes via \`Start-Process\` or \`Start-Job\`."`
- `getToolArgument("shell")` 的 `"either \"powershell\" (default) or \"cmd\""` 保持原版
  （未随本次改动；如需告知模型可传 `pwsh` 参数，可再扩一个 LDC 替换点）

已随主补丁落地：探测仍是一次性（resolver 进程级缓存），文案按探测结果二选一。

## 6. 构建与验证改动

### 6.1 PatchTool（`src/patcher/java/PatchTool.java`）

- main 增加 `case "winshell": patchRunShellHandler(args[1], args[2]); break;`
- 新方法 `patchRunShellHandler(Path in, Path out)`：按第 4 节三个注入点实现；
  定位失败抛 `IllegalStateException`（与既有代码风格一致，防静默漏改）

### 6.2 `scripts/30_build_patch.sh`

- 新阶段（放阶段 4 javac 之后，命名 [6/13]）：`PatchTool winshell ...`
- 阶段 4 的 `find src/main/java` 自动包含新类，无需改
- 组装 `jar uf` 增加：
  - `-C "$PATCHED" "com/google/aiplugin/agents/tools/execute/RunShellCommandHandler.class"`
  - `-C "$OUT" "com/google/aiplugin/agents/tools/execute/WindowsShellResolver.class"`

### 6.3 `scripts/40_verify.sh` 与 Linux 测试方案

开发/CI 环境是 Ubuntu，本补丁在 Linux 上**高度可测**，依据：

1. `createProcessArgs$aiplugin_agents_agents_core` 的 `isWindows` 是**显式参数**（
   `(cli, tempFile, usePty, resolveShellPath, isWindows, cont)`），Windows 分支可不经
   Windows 系统直接以 `isWindows=true` 调用验证；
2. `WindowsShellResolver` 探测逻辑拆成纯函数（`decide(hits)`）＋可注入
   （`setForTesting`），决策表全分支在 Linux 可测；
3. 行为测试对补丁 jar 编译运行（既有 RT_CP 模式），跑的是补丁后真实字节码。

测试分四层：

**L1 结构层**（`WindowsShellPatchStructureTest.java`，仿 UiLoadTest 的 ASM 读法）：
- 补丁后 `RunShellCommandHandler` 不再含 `LDC "powershell.exe"`；
- 存在 `INVOKESTATIC defaultShellName` 且位于 `ILOAD 5(＝isWindows) → IFEQ` 分支内；
- 存在 `INVOKESTATIC normalizeShell` 且位置在 `ASTORE 8` 之后、第一次
  `LDC "powershell"`（redirect 判定）之前（保证归一化覆盖所有下游使用点）；
- 存在 `INVOKESTATIC executable` 且位于 `ANEWARRAY String` 之后（powershell 数组上下文）。

**L2 单元层**（`WindowsShellResolverTest.java`）：
- `decide(hits)` 纯函数全表：真实路径 → true；混合（真实+别名）→ true；仅
  WindowsApps 别名 → 走 verify（Linux 上路径不存在 → false，顺便真实覆盖“未装 shim”
  回退）；空 → false；
- `where()` 真实调用：Linux 上 `where.exe` 不存在 → IOException → 空列表（“探测失败
  回退”路径真实验证）；
- `verifyExecutable(不存在路径)` → false（异常兜底）；
- `setForTesting(true/false/null)` 决议 + `normalizeShell` 幂等/大小写（`PwSh`→powershell）。

**L3 行为层**（`RunShellCommandWindowsArgTest.java`）：
- 构造：`ToolContext` 用 JDK 动态代理（createProcessArgs 不触碰 context，代理方法
  永不触发）＋ `new RunShellCommandHandler(ctx, new RunShellCommandArgs(...))`；
- suspend 调用：`createProcessArgs$aiplugin_agents_agents_core(cli, null, usePty, false,
  true, cont)`；`resolveShellPath=false` 无挂起点直接返回 Pair，Continuation +
  COROUTINE_SUSPENDED 检测＋CountDownLatch 兜底；
- 断言矩阵（`setForTesting(true)` 前置，见下文表）；
- 额外两条纯 Java 断言：① `-EncodedCommand` 参数 Base64(UTF-16LE) 解码等于原始 cli
  （编码链路验证，跨平台）；② `setForTesting(false)` ＋ `isWindows=false` 回归比对
  （补丁后 Unix 分支仍产出 `/bin/`+`/usr/bin/` bash 数组，证明未碰坏非 Windows 路径）。

| # | shell | cli | usePty | 期望 |
|---|---|---|---|---|
| 1 | `""`（缺省） | `Write-Host hi` | true | `[pwsh.exe, -EncodedCommand, B64]` |
| 2 | `"pwsh"` | 同上 | true | 同左（归一化生效） |
| 3 | `"powershell"` | 同上 | true | `[pwsh.exe, ...]`（pwsh 优先） |
| 4 | `"cmd"` | `dir` | true | `[cmd.exe, /c, dir]` |
| 5 | `"bash"` | 任意 | true | error 含 `"not supported on Windows"` |
| 6 | `"powershell"` | `cmd /c dir` | true | `[cmd.exe, /c, dir]`（cmd wrapper 仍在） |
| 7 | `""` | 任意 | false | 含 `-NonInteractive` |
| 8 | `setForTesting(false)`＋`""` | 任意 | true | `[powershell.exe, ...]`（回退＝现状） |

**L4 回归层**：40_verify.sh 现有 11 步全量保持；新增 [12/13][13/13] 两个环节（编号
顺延），CheckClassAdapter 列表加 `RunShellCommandHandler`。

**可选增强**：Ubuntu 装 Microsoft 官方 Linux 版 pwsh 做 skip-if-absent 冒烟——若
`where("pwsh")` 命中，真实执行 `pwsh -EncodedCommand <B64>` 验证编码通道
（pwsh 的 `-EncodedCommand`/`-NonInteractive` 为跨平台行为）；未安装则 SKIP，
verify 保持全绿。

## 7. 风险与边界

1. **WindowsApps shim 坑**：Store 别名未安装也存在（启动被拦截弹商店）；方案以 `where.exe`
   输出剔除 WindowsApps 行判定真实安装；**仅**命中别名时用 `pwsh -Command "$true"`
   执行验证区分已装/未装，避免误判商店安装为不可用、也避免把未装别名当真
2. **探测开销**：一次性 `where.exe`（~100ms）+ 罕见场景下的一次 pwsh 启动验证（~500ms），
   之后进程级缓存；安装/卸载 pwsh 需重启 IDE 生效（如需热更新可加 TTL，本期不做）
3. **行为差异**：有 pwsh 的机器默认宿主从 5.1 变 7——`$PSVersionTable`、默认 profile、
   模块路径不同；对随机命令影响有限（工具只换宿主，不注入脚本），文档注明即可；
   pwsh 默认 UTF-8 输出反而改善中文 Windows 下 5.1 OEM 代码页乱码（附带收益，
   `drainStream` 固定按 UTF-8 解码）
4. **并发**：`isPwshAvailable` 双检锁；`where.exe`/pwsh 验证均带超时兜底，不阻塞执行
5. **回退保证**：探测异常/超时/无 pwsh → `powershell.exe`（现状路径零改动）
6. **非 Windows**：resolver 恒 false，方法 isWindows 参数由调用方传入，
   非 Windows 路径完全不受影响
7. **可选 flag**：如需可控性，可仿 `StudioBotFlags`（isReplaceTextToolEnabled 模式）
   加 `runShellPreferPwsh`（默认 true）；本期默认开启，flag 接线列为 P2 增量

## 8. 实施步骤（全部已完成）

1. `src/main/java/.../execute/WindowsShellResolver.java`（第 3 节源码）—— 完毕
2. `PatchTool`：+`case "winshell"` 与 `patchRunShell`（行为层 3 注入点 + 文案层 2 处，第 4/5 节）—— 完毕
3. `30_build_patch.sh`：新阶段 + 组装两项（第 6.2 节，现为阶段 11/12）—— 完毕
4. `src/test/java/WindowsShellResolverTest.java`、`RunShellCommandWindowsArgTest.java` —— 完毕
5. `40_verify.sh`：字节码校验 + 两个新环节（第 6.3 节，现为 [12/13][13/13]）—— 完毕
6. `RunShellCommandTool` 文案层 LDC 替换（第 5 节）—— 完毕（提交 0bcc5ed）
7. 回归：完整 `30_build_patch.sh` + `40_verify.sh`，确认无 pwsh 模拟下
   （`setForTesting(false)`）行为与现状逐位一致 —— 完毕；Windows 真机人工验证见第 9 节

## 9. 人工验证记录（Windows 真机，2026-09）

环境：Windows 11 专业版 10.0.26200 x64，已装 PowerShell 7.6.5（`C:\Program Files\PowerShell\7\pwsh.exe`）。
验证产物：`dist/aiplugin-patched.jar`（提交 0bcc5ed 后版本）。

| 项目 | 实测 | 结论 |
|---|---|---|
| 缺省 shell（shell 空） | `(Get-Process -Id $PID).Path` → `pwsh.exe`；`$PSVersionTable` 7.6.5 Core | ✅ pwsh 优先生效 |
| `shell="cmd"` | `cmd.exe`，`%COMSPEC%`/`ver` 正常 | ✅ cmd 分支未破坏 |
| 每次调用 | 不同 PID（30264/25700）→ 无会话持久化 | ✅ 符合设计 |
| 沙箱 | `isSandboxed: false` | ✅ Windows 无原生沙箱 |
| 文案层 | 模型原样引用的定义含 `PowerShell 7 (the default)` 与 `pwsh.exe -Command` | ✅ summaryForWindows/descriptionForWindows 生效 |

未覆盖：无 pwsh 真机场景（回退路径由 `setForTesting(false)` 测试注入覆盖，行为与补丁前一致）。