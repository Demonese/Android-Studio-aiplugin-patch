package com.google.aiplugin.agents.tools.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 平台的 PowerShell 宿主探测：优先 PowerShell 7 (pwsh)，
 * 未安装时回退 Windows PowerShell 5.1 (powershell.exe)。
 *
 * 探测主手段：`where.exe pwsh.exe`（Windows 自带 PATH 全量搜索，按 PATHEXT 追加
 * 扩展名、输出所有匹配绝对路径，与 PtyProcessBuilder 实际解析一致）。
 * WindowsApps 别名（Microsoft Store 的 app-execution-alias）未安装时也存在于
 * %LOCALAPPDATA%\Microsoft\WindowsApps\ 且会出现在 where 输出中，因此：
 *   - where 输出含非 WindowsApps 路径 → 真实安装，直接可用；
 *   - where 输出仅 WindowsApps 别名 → 执行 pwsh 本体验证（区分已装/未装 shim）；
 *   - 探测失败/异常/超时 → 一律回退 powershell.exe（现状），不向执行路径抛异常。
 *
 * 探测结果进程级缓存（安装/卸载 pwsh 需重启 IDE 生效）。
 * 仅 Windows 使用；非 Windows 恒返回 powershell 家族现状。
 */
public final class WindowsShellResolver {
    /** 加速用 fast-path（标准安装直接命中，省一次子进程；不作为判定依据） */
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

    /** 探测 pwsh 是否可用（首次调用后缓存；非 Windows 恒 false）。
     *  注意：先查缓存再查 os.name，保证测试注入（setForTesting）在非 Windows 也生效。 */
    public static boolean isPwshAvailable() {
        if (probed) {
            return pwshAvailable;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
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
        for (String pattern : FAST_PATHS) {
            String path = expandEnv(pattern);
            if (path != null && new File(path).isFile()) {
                return true;
            }
        }
        return decide(where("pwsh.exe"));
    }

    /**
     * 纯决策函数（与进程调用解耦，便于全平台测试）：
     *   - hits 含非 WindowsApps 路径 → 真实安装，true；
     *   - hits 仅 WindowsApps 别名（商店安装场景）→ 执行验证区分已装/未装；
     *   - 空 → false。
     */
    @VisibleForTesting
    public static boolean decide(@NotNull List<String> hits) {
        for (String h : hits) {
            if (!h.contains("WindowsApps")) {
                return true;
            }
        }
        return !hits.isEmpty() && verifyExecutable(hits.get(0));
    }

    /** 执行 where.exe 全量搜索（2s 超时），返回所有匹配绝对路径（按 PATH 顺序）。
     *  探测失败（如非 Windows 环境无 where.exe）返回空列表 → 回退 powershell.exe。 */
    @VisibleForTesting
    public static List<String> where(String exeName) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("where.exe", exeName)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    /** 对候选执行 pwsh 本体验证（3s 超时，退出码 0 即真可用）。
     *  仅用于唯一候选是 WindowsApps 别名时。 */
    @VisibleForTesting
    public static boolean verifyExecutable(String pwshPath) {
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
        Matcher m = Pattern.compile("%([A-Za-z0-9()]+)%").matcher(pattern);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(env != null ? env : m.group(0)));
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