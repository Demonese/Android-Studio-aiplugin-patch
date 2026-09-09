import com.google.aiplugin.agents.tools.execute.RunShellCommandArgs;
import com.google.aiplugin.agents.tools.execute.RunShellCommandHandler;
import com.google.aiplugin.agents.tools.execute.WindowsShellResolver;
import com.google.studiobot.agentsdk.tools.ToolContext;
import com.google.studiobot.datamodel.tools.Response;

import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlin.Pair;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * run_shell_command 的 Windows 分支行为测试（Linux 上以 isWindows=true 直呼
 * createProcessArgs$aiplugin_agents_agents_core）：
 *   - 补丁后命令数组矩阵：默认(pwsh 优先) / 显式 pwsh / powershell / cmd / 不支持 shell
 *   - cmd wrapper（cmd /c 前缀自动切换）
 *   - 非 PTY 时 -NonInteractive
 *   - -EncodedCommand Base64(UTF-16LE) 编码链路
 *   - 无 pwsh 环境回退 powershell.exe（与现状一致）
 *   - Unix 分支回归（isWindows=false 不受补丁影响）
 */
public class RunShellCommandWindowsArgTest {

    static int failures = 0;
    static final Charset UTF16LE = StandardCharsets.UTF_16LE;

    static void check(String name, boolean cond) {
        System.out.println((cond ? "  ok: " : "FAILED: ") + name);
        if (!cond) failures++;
    }

    /** 动态代理 ToolContext（createProcessArgs 不触碰 context，代理方法永不触发）。 */
    static ToolContext proxyContext() {
        return (ToolContext) Proxy.newProxyInstance(
                ToolContext.class.getClassLoader(),
                new Class<?>[]{ToolContext.class},
                (p, m, a) -> null);
    }

    /** 调用补丁后的 suspend 方法，解析返回 Pair<String[], Response>。 */
    @SuppressWarnings("unchecked")
    static Pair<String[], Response> call(String cli, String shell, boolean usePty, boolean isWindows) {
        RunShellCommandArgs args = new RunShellCommandArgs(cli, "test desc", "", shell, false, 60000L);
        RunShellCommandHandler h = new RunShellCommandHandler(proxyContext(), args);
        CountDownLatch latch = new CountDownLatch(1);
        final Object[] out = new Object[1];
        Continuation<Object> cont = new Continuation<Object>() {
            @Override
            public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(Object result) {
                out[0] = result;
                latch.countDown();
            }
        };
        Object r = h.createProcessArgs$aiplugin_agents_agents_core(cli, null, usePty, false, isWindows, cont);
        if (r == IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            out[0] = r;
        }
        return (Pair<String[], Response>) out[0];
    }

    static String[] cmdArray(Pair<String[], Response> pair) {
        return pair == null ? null : pair.component1();
    }

    static Response resp(Pair<String[], Response> pair) {
        return pair == null ? null : pair.component2();
    }

    static String encodedCommand(String[] arr) {
        for (int i = 0; i < arr.length - 1; i++) {
            if ("-EncodedCommand".equals(arr[i])) return arr[i + 1];
        }
        return null;
    }

    public static void main(String[] args) {
        WindowsShellResolver.setForTesting(true); // 模拟：环境存在 pwsh

        // 矩阵 1：缺省 shell → pwsh.exe + -EncodedCommand
        Pair<String[], Response> p = call("Write-Host hi", "", true, true);
        String[] a = cmdArray(p);
        check("win 默认 shell → 首元素 pwsh.exe", a != null && "pwsh.exe".equals(a[0]));
        check("win 默认 → 含 -EncodedCommand", a != null && encodedCommand(a) != null);
        check("win 默认 → 编码链路 Base64(UTF-16LE) 还原命令",
                a != null && "Write-Host hi".equals(
                        new String(Base64.getDecoder().decode(encodedCommand(a)), UTF16LE)));

        // 矩阵 2：显式 pwsh → 归一化生效，同为 pwsh.exe
        a = cmdArray(call("Write-Host hi", "pwsh", true, true));
        check("win shell=pwsh → 首元素 pwsh.exe（归一化生效）", a != null && "pwsh.exe".equals(a[0]));

        // 矩阵 3：显式 powershell → pwsh 优先策略
        a = cmdArray(call("Write-Host hi", "powershell", true, true));
        check("win shell=powershell → 仍优先 pwsh.exe", a != null && "pwsh.exe".equals(a[0]));

        // 矩阵 4：cmd（非交互时原设计会附加 " < NUL" 重定向到命令尾部）
        a = cmdArray(call("dir", "cmd", true, true));
        check("win shell=cmd → [cmd.exe, /c, dir < NUL]", a != null
                && a.length == 3 && "cmd.exe".equals(a[0]) && "/c".equals(a[1])
                && "dir < NUL".equals(a[2]));

        // 矩阵 5：不支持的 shell
        Response r = resp(call("x", "bash", true, true));
        check("win shell=bash → 报 not supported on Windows",
                r != null && r.getExecutionStatus() != null
                        && String.valueOf(r.getExecutionStatus()).contains("ERROR")
                        && r.toString().contains("not supported on Windows"));

        // 矩阵 6：cmd wrapper（powershell + "cmd /c ..." 开头）→ 自动切 cmd
        a = cmdArray(call("cmd /c dir", "powershell", true, true));
        check("win cmd wrapper → [cmd.exe, /c, dir]",
                a != null && a.length == 3
                        && "cmd.exe".equals(a[0]) && "/c".equals(a[1]) && "dir".equals(a[2]));

        // 矩阵 7：非 PTY → 含 -NonInteractive
        a = cmdArray(call("Write-Host hi", "", false, true));
        boolean hasNI = false;
        if (a != null) for (String s : a) if ("-NonInteractive".equals(s)) hasNI = true;
        check("win usePty=false → 含 -NonInteractive", a != null && hasNI && "pwsh.exe".equals(a[0]));

        // 矩阵 8：无 pwsh → 回退 powershell.exe（与现状一致）
        WindowsShellResolver.setForTesting(false);
        a = cmdArray(call("Write-Host hi", "", true, true));
        check("win 无 pwsh → 退回首元素 powershell.exe", a != null && "powershell.exe".equals(a[0]));
        check("win 无 pwsh → -EncodedCommand 仍可用",
                a != null && encodedCommand(a) != null
                        && "Write-Host hi".equals(
                                new String(Base64.getDecoder().decode(encodedCommand(a)), UTF16LE)));

        // Unix 分支回归：isWindows=false 不调用 resolver（Linux 真实路径不受影响）
        WindowsShellResolver.setForTesting(true); // 即使探测结果被注入为 true
        a = cmdArray(call("echo hi", "", true, false));
        check("unix 分支回归 → 首元素为 bash 系（含 bash）", a != null && a[0].contains("bash"));
        check("unix 分支回归 → 不出现 pwsh.exe/powershell.exe",
                a != null && !a[0].contains("pwsh.exe") && !a[0].contains("powershell.exe"));

        // getToolDescription 回归：Linux（isWindows=false）仍返回 bash 文案，
        // 证明补丁未破坏 Unix 分支；Windows 分支文案由 Resolver 单元测试覆盖
        com.google.aiplugin.agents.tools.execute.RunShellCommandTool tool =
                new com.google.aiplugin.agents.tools.execute.RunShellCommandTool();
        com.google.studiobot.agentsdk.tools.ToolDescription td = tool.getToolDescription();
        check("unix ToolDescription → summary 为 bash 文案（isWindows=false）",
                td != null && "Executes a shell command in bash (the default), zsh or sh".equals(td.summary()));

        WindowsShellResolver.setForTesting(null);
        System.out.println(failures == 0 ? "WINDOWS_ARG_OK" : "WINDOWS_ARG_FAILED: " + failures);
        if (failures != 0) System.exit(1);
    }
}