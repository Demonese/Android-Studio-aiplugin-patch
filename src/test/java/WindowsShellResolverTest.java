import com.google.aiplugin.agents.tools.execute.WindowsShellResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * WindowsShellResolver 单元测试（Linux 可跑）：
 *   - normalizeShell 归一化与幂等
 *   - setForTesting 注入的决议（defaultShellName / executable）
 *   - decide() 纯函数决策表（真实安装 / 混合 / 仅 WindowsApps 别名 / 空）
 *   - where()/verifyExecutable() 在非 Windows 环境的失败回退路径
 *   - isPwshAvailable() 在非 Windows 恒 false
 */
public class WindowsShellResolverTest {

    static int failures = 0;

    static void check(String name, boolean cond) {
        System.out.println((cond ? "  ok: " : "FAILED: ") + name);
        if (!cond) failures++;
    }

    public static void main(String[] args) {
        // --- normalizeShell ---
        check("normalizeShell pwsh -> powershell",
                "powershell".equals(WindowsShellResolver.normalizeShell("pwsh")));
        check("normalizeShell PWSH (upper) -> powershell",
                "powershell".equals(WindowsShellResolver.normalizeShell("PWSH")));
        check("normalizeShell PwSh (mixed) -> powershell",
                "powershell".equals(WindowsShellResolver.normalizeShell("PwSh")));
        check("normalizeShell powershell 原样",
                "powershell".equals(WindowsShellResolver.normalizeShell("powershell")));
        check("normalizeShell cmd 原样",
                "cmd".equals(WindowsShellResolver.normalizeShell("cmd")));
        check("normalizeShell bash 原样",
                "bash".equals(WindowsShellResolver.normalizeShell("bash")));
        check("normalizeShell 空串原样",
                "".equals(WindowsShellResolver.normalizeShell("")));
        check("normalizeShell 幂等（两次归一化不变）",
                "powershell".equals(WindowsShellResolver.normalizeShell(WindowsShellResolver.normalizeShell("pwsh"))));

        // --- setForTesting 注入决议 ---
        WindowsShellResolver.setForTesting(true);
        check("setForTesting(true) defaultShellName=pwsh",
                "pwsh".equals(WindowsShellResolver.defaultShellName()));
        check("setForTesting(true) executable=pwsh.exe",
                "pwsh.exe".equals(WindowsShellResolver.executable()));

        WindowsShellResolver.setForTesting(false);
        check("setForTesting(false) defaultShellName=powershell（现状）",
                "powershell".equals(WindowsShellResolver.defaultShellName()));
        check("setForTesting(false) executable=powershell.exe（现状）",
                "powershell.exe".equals(WindowsShellResolver.executable()));

        // --- 文案决议（getToolDescription 的 Windows 分支） ---
        WindowsShellResolver.setForTesting(true);
        check("pwsh 可用 → summary 明确 PowerShell 7",
                "Executes a shell command in PowerShell 7 (the default) or cmd on Windows"
                        .equals(WindowsShellResolver.summaryForWindows()));
        check("pwsh 可用 → description 为 pwsh.exe -Command",
                "Executes as `pwsh.exe -Command <command>`. Supports background processes via `Start-Process` or `Start-Job`."
                        .equals(WindowsShellResolver.descriptionForWindows()));

        WindowsShellResolver.setForTesting(false);
        check("无 pwsh → summary 保持原版文案",
                "Executes a shell command in PowerShell (the default) or cmd on Windows"
                        .equals(WindowsShellResolver.summaryForWindows()));
        check("无 pwsh → description 保持原版 powershell.exe 文案",
                "Executes as `powershell.exe -Command <command>`. Supports background processes via `Start-Process` or `Start-Job`."
                        .equals(WindowsShellResolver.descriptionForWindows()));

        // --- decide() 纯函数决策表 ---
        List<String> realOnly = Arrays.asList("C:\\Program Files\\PowerShell\\7\\pwsh.exe");
        check("decide 真实安装路径 -> true",
                WindowsShellResolver.decide(realOnly));

        List<String> mixed = Arrays.asList(
                "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
                "C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\pwsh.exe");
        check("decide 混合（真实+别名）-> true（别名被剔除）",
                WindowsShellResolver.decide(mixed));

        List<String> aliasOnly = Arrays.asList(
                "C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\pwsh.exe");
        // Linux 上别名路径不存在 → verifyExecutable 失败 → false（等同“未装 shim”回退）
        check("decide 仅 WindowsApps 别名 -> 执行验证失败则 false",
                !WindowsShellResolver.decide(aliasOnly));

        check("decide 空列表 -> false",
                !WindowsShellResolver.decide(Collections.emptyList()));

        // --- 非 Windows 环境的真实探测路径 ---
        check("where() 在无 where.exe 环境 -> 空列表（探测失败回退）",
                WindowsShellResolver.where("pwsh.exe").isEmpty());

        check("verifyExecutable(不存在路径) -> false（异常兜底）",
                !WindowsShellResolver.verifyExecutable("/nonexistent/pwsh.exe"));

        // 复位后真实探测：非 Windows 恒 false
        WindowsShellResolver.setForTesting(null);
        check("isPwshAvailable() 非 Windows -> false",
                !WindowsShellResolver.isPwshAvailable());

        System.out.println(failures == 0 ? "RESOLVER_OK" : "RESOLVER_FAILED: " + failures);
        if (failures != 0) System.exit(1);
    }
}