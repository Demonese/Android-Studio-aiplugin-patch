import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class PatchTool {
    static final String OPENAI_TYPE = "com/android/studio/ml/modelproviders/data/OpenAiApiType";
    static final String CONVERTER = "com/android/studio/ml/modelproviders/data/OpenAiApiTypeConverter";
    static final String RPD = "com/android/studio/ml/modelproviders/data/ProviderData$RemoteProviderData";
    static final String API_SCHEMA = RPD + "$ApiSchema";
    static final String UI = "com/android/studio/ml/backends/settings/OpenAiApiTypeUi";
    static final String PANEL = "com/android/studio/ml/backends/settings/RemoteModelProviderInfoPanel";
    static final String MODEL_API = "com/android/studio/ml/backends/openai/OpenAiModelApi";
    static final String CATCH1 = MODEL_API + "$streamGenerateContent$1";
    static final String PROVIDER = "com/android/studio/ml/backends/openai/OpenAiModelApiProvider";
    static final String SUPPORT = "com/android/studio/ml/backends/openai/OpenAiApiTypeSupport";
    static final String RESPONSES_V2 = "com/android/studio/ml/backends/openai/OpenAiResponsesApiV2";
    static final String RESPONSES_SUPPORT = "com/android/studio/ml/backends/openai/OpenAiResponsesSupport";
    static final String COMPLETION_V2 = "com/android/studio/ml/backends/openai/OpenAiCompletionApiV2";
    static final String COMPLETION_SUPPORT = "com/android/studio/ml/backends/openai/OpenAiCompletionSupport";
    static final String ASSISTANT_BUILDER = "com/openai/models/chat/completions/ChatCompletionAssistantMessageParam$Builder";
    static final String MSG_PARAM = "com/openai/models/chat/completions/ChatCompletionMessageParam";
    static final String CHAT_MESSAGE = "com/google/studiobot/datamodel/models/ModelChatMessage";

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        switch (cmd) {
            case "data": patchData(args[1], args[2]); break;
            case "panel": patchPanel(args[1], args[2]); break;
            case "api": patchApi(args[1], args[2]); break;
            default: throw new IllegalArgumentException(cmd);
        }
    }

    static byte[] readClass(Path jarDir, String internalName) throws IOException {
        return Files.readAllBytes(jarDir.resolve(internalName + ".class"));
    }

    static void writeClass(Path outDir, String internalName, byte[] bytes) throws IOException {
        Path p = outDir.resolve(internalName + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }

    static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        throw new IllegalStateException("method not found: " + name + " " + desc);
    }

    static AbstractInsnNode lastOpcode(MethodNode m, int opcode) {
        AbstractInsnNode last = null;
        for (AbstractInsnNode in : m.instructions) {
            if (in.getOpcode() == opcode) last = in;
        }
        if (last == null) throw new IllegalStateException("opcode not found: " + opcode + " in " + m.name);
        return last;
    }

    static AbstractInsnNode findLdc(MethodNode m, String value) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof LdcInsnNode && value.equals(((LdcInsnNode) in).cst)) return in;
        }
        throw new IllegalStateException("ldc not found: " + value + " in " + m.name);
    }

    static AbstractInsnNode findInvoke(MethodNode m, String owner, String name, String desc) {
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if (mi.owner.equals(owner) && mi.name.equals(name) && (desc == null || mi.desc.equals(desc))) return in;
            }
        }
        throw new IllegalStateException("invoke not found: " + owner + "." + name + desc + " in " + m.name);
    }

    static FieldNode addField(ClassNode cn) {
        FieldNode f = new FieldNode(ACC_PRIVATE, "openAiApiType", "L" + OPENAI_TYPE + ";", null, null);
        AnnotationVisitor av = f.visitAnnotation("Lcom/intellij/util/xmlb/annotations/OptionTag;", true);
        av.visit("converter", Type.getType("L" + CONVERTER + ";"));
        av.visitEnd();
        cn.fields.add(f);
        return f;
    }

    static void addGetterSetter(ClassNode cn) {
        MethodNode get = new MethodNode(ACC_PUBLIC | ACC_FINAL, "getOpenAiApiType", "()L" + OPENAI_TYPE + ";", null, null);
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(1, 1);
        get.visitEnd();
        cn.methods.add(get);

        MethodNode set = new MethodNode(ACC_PUBLIC | ACC_FINAL, "setOpenAiApiType", "(L" + OPENAI_TYPE + ";)V", null, null);
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(PUTFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";");
        set.visitInsn(RETURN);
        set.visitMaxs(2, 2);
        set.visitEnd();
        cn.methods.add(set);
    }

    static InsnList autoInit() {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new FieldInsnNode(GETSTATIC, OPENAI_TYPE, "AUTO", "L" + OPENAI_TYPE + ";"));
        l.add(new FieldInsnNode(PUTFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        return l;
    }

    static void patchData(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, RPD)).accept(cn, 0);

        addField(cn);
        addGetterSetter(cn);

        MethodNode ctor = findMethod(cn, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;L" + API_SCHEMA + ";)V");
        ctor.instructions.insertBefore(lastOpcode(ctor, RETURN), autoInit());

        MethodNode copy0 = findMethod(cn, "copy", "()L" + RPD + ";");
        AbstractInsnNode ret = lastOpcode(copy0, ARETURN);
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new FieldInsnNode(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        l.add(new FieldInsnNode(PUTFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        copy0.instructions.insertBefore(ret, l);

        MethodNode copy4 = findMethod(cn, "copy", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;L" + API_SCHEMA + ";)L" + RPD + ";");
        InsnList l2 = new InsnList();
        l2.add(new InsnNode(DUP));
        l2.add(new VarInsnNode(ALOAD, 0));
        l2.add(new FieldInsnNode(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        l2.add(new FieldInsnNode(PUTFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        copy4.instructions.insertBefore(lastOpcode(copy4, ARETURN), l2);

        patchEquals(cn);
        patchHashCode(cn);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, RPD, cw.toByteArray());
        System.out.println("patched " + RPD);
    }

    // Kotlin data class 生成的 equals 只比较构造器属性，忽略 ASM 新增字段，
    // 导致 ModelProviderConfigurable.isModified() 检测不到 openAiApiType 变更
    // （Apply 按钮不亮、OK 时 ConfigurableEditor 因 Apply 未启用而跳过 apply）。
    // 原 "return true" 路径为 [Label][Frame][ICONST_1][IRETURN]，其中 Label 是
    // schema 比较 if_acmpeq 的跳转目标。把 openAiApiType 比较插到 Frame 之后、
    // ICONST_1 之前：原跳转先经过新检查，新分支目标用 F_SAME 帧（状态与原帧相同）。
    static void patchEquals(ClassNode cn) {
        MethodNode eq = findMethod(cn, "equals", "(Ljava/lang/Object;)Z");
        AbstractInsnNode lastRet = lastOpcode(eq, IRETURN);
        AbstractInsnNode iconst1 = lastRet.getPrevious();
        if (iconst1.getOpcode() != ICONST_1) throw new IllegalStateException("expected ICONST_1 before final IRETURN in equals");
        LabelNode ok = new LabelNode();
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new FieldInsnNode(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new FieldInsnNode(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        l.add(new JumpInsnNode(IF_ACMPEQ, ok));
        l.add(new InsnNode(ICONST_0));
        l.add(new InsnNode(IRETURN));
        l.add(ok);
        l.add(new FrameNode(F_SAME, 0, null, 0, null));
        eq.instructions.insertBefore(iconst1, l);
    }

    // hashCode 末尾为 ILOAD 1; IRETURN（result 存于局部变量1），
    // 在其前插入 result = result * 31 + openAiApiType.hashCode()（字段恒非 null）。
    static void patchHashCode(ClassNode cn) {
        MethodNode hc = findMethod(cn, "hashCode", "()I");
        AbstractInsnNode lastRet = lastOpcode(hc, IRETURN);
        AbstractInsnNode iload = lastRet.getPrevious();
        if (iload.getOpcode() != ILOAD) throw new IllegalStateException("expected ILOAD before final IRETURN in hashCode");
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ILOAD, 1));
        l.add(new IntInsnNode(BIPUSH, 31));
        l.add(new InsnNode(IMUL));
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new FieldInsnNode(GETFIELD, RPD, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        l.add(new MethodInsnNode(INVOKEVIRTUAL, OPENAI_TYPE, "hashCode", "()I", false));
        l.add(new InsnNode(IADD));
        l.add(new VarInsnNode(ISTORE, 1));
        hc.instructions.insertBefore(iload, l);
    }

    static void patchPanel(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, PANEL)).accept(cn, 0);

        MethodNode setupUi = findMethod(cn, "setupUi", "(Lcom/intellij/ui/dsl/builder/Panel;)V");
        AbstractInsnNode apikeyLdc = findLdc(setupUi, "remote.studiobot.settings.apikey.title");
        AbstractInsnNode anchor = apikeyLdc.getPrevious();
        while (!(anchor.getOpcode() >= 25 && anchor.getOpcode() <= 29)) {
            anchor = anchor.getPrevious();
        }
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new MethodInsnNode(INVOKESTATIC, UI, "addRow", "(L" + PANEL + ";Lcom/intellij/ui/dsl/builder/Panel;)V", false));
        setupUi.instructions.insertBefore(anchor, l);

        MethodNode update = findMethod(cn, "update", "()V");
        AbstractInsnNode getSchema = findInvoke(update, RPD, "getSchema", "()L" + API_SCHEMA + ";");
        AbstractInsnNode setCall = getSchema;
        while (setCall != null && !(setCall instanceof MethodInsnNode && ((MethodInsnNode) setCall).owner.equals("com/intellij/openapi/observable/properties/AtomicProperty") && ((MethodInsnNode) setCall).name.equals("set"))) {
            setCall = setCall.getNext();
        }
        if (setCall == null) throw new IllegalStateException("AtomicProperty.set after getSchema not found");
        InsnList l2 = new InsnList();
        l2.add(new VarInsnNode(ALOAD, 0));
        l2.add(new VarInsnNode(ALOAD, 3));
        l2.add(new MethodInsnNode(INVOKESTATIC, UI, "load", "(L" + PANEL + ";L" + RPD + ";)V", false));
        update.instructions.insert(setCall, l2);

        MethodNode lambda4 = findMethod(cn, "_init_$lambda$4", "(L" + PANEL + ";L" + API_SCHEMA + ";)Lkotlin/Unit;");
        AbstractInsnNode setSchema = findInvoke(lambda4, RPD, "setSchema", "(L" + API_SCHEMA + ";)V");
        InsnList l3 = new InsnList();
        l3.add(new VarInsnNode(ALOAD, 0));
        l3.add(new VarInsnNode(ALOAD, 1));
        l3.add(new MethodInsnNode(INVOKESTATIC, UI, "syncVisibility", "(L" + PANEL + ";L" + API_SCHEMA + ";)V", false));
        lambda4.instructions.insert(setSchema, l3);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, PANEL, cw.toByteArray());
        System.out.println("patched " + PANEL);
    }

    // 阶段：让 openAiApiType 设置真正生效（协议选择 + 禁用自动回退）
    static void patchApi(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        patchModelApi(in, out);
        patchCatchHandler(in, out);
        patchProvider(in, out);
        patchResponsesApi(in, out);
        patchCompletionApi(in, out);
    }

    // OpenAiModelApi：加 openAiApiType 字段/getter/setter（默认 AUTO），
    // 并把 streamGenerateContent 里的 supportResponses.get()
    // 替换为 OpenAiApiTypeSupport.resolveUseResponses(this)：
    //   CHAT_COMPLETION -> 恒 false；RESPONSE -> 恒 true；AUTO -> 原逻辑
    static void patchModelApi(Path in, Path out) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, MODEL_API)).accept(cn, 0);

        cn.fields.add(new FieldNode(ACC_PRIVATE, "openAiApiType", "L" + OPENAI_TYPE + ";", null, null));

        MethodNode get = new MethodNode(ACC_PUBLIC | ACC_FINAL, "getOpenAiApiType", "()L" + OPENAI_TYPE + ";", null, null);
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, MODEL_API, "openAiApiType", "L" + OPENAI_TYPE + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(1, 1);
        get.visitEnd();
        cn.methods.add(get);

        MethodNode set = new MethodNode(ACC_PUBLIC | ACC_FINAL, "setOpenAiApiType", "(L" + OPENAI_TYPE + ";)V", null, null);
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(PUTFIELD, MODEL_API, "openAiApiType", "L" + OPENAI_TYPE + ";");
        set.visitInsn(RETURN);
        set.visitMaxs(2, 2);
        set.visitEnd();
        cn.methods.add(set);

        MethodNode ctor = findMethod(cn, "<init>", null);
        InsnList init = new InsnList();
        init.add(new VarInsnNode(ALOAD, 0));
        init.add(new FieldInsnNode(GETSTATIC, OPENAI_TYPE, "AUTO", "L" + OPENAI_TYPE + ";"));
        init.add(new FieldInsnNode(PUTFIELD, MODEL_API, "openAiApiType", "L" + OPENAI_TYPE + ";"));
        ctor.instructions.insertBefore(lastOpcode(ctor, RETURN), init);

        MethodNode sgc = findMethod(cn, "streamGenerateContent", "(Lcom/google/studiobot/datamodel/models/ModelRequest;)Lkotlinx/coroutines/flow/Flow;");
        AbstractInsnNode gf = null;
        for (AbstractInsnNode n = sgc.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GETFIELD && n instanceof FieldInsnNode
                    && ((FieldInsnNode) n).owner.equals(MODEL_API) && ((FieldInsnNode) n).name.equals("supportResponses")) {
                gf = n;
                break;
            }
        }
        if (gf == null) throw new IllegalStateException("supportResponses getfield not found in streamGenerateContent");
        AbstractInsnNode getCall = gf.getNext();
        if (getCall.getOpcode() != INVOKEVIRTUAL) throw new IllegalStateException("expected AtomicBoolean.get after supportResponses getfield");
        sgc.instructions.set(gf, new MethodInsnNode(INVOKESTATIC, SUPPORT, "resolveUseResponses", "(L" + MODEL_API + ";)Z", false));
        sgc.instructions.remove(getCall);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, MODEL_API, cw.toByteArray());
        System.out.println("patched " + MODEL_API);
    }

    // catch 处理器（FlowKt.catch 的 lambda）：Responses 请求失败时原逻辑为
    //   (BAD_REQUEST_OTHER || NOT_FOUND) && $useResponsesAPI -> supportResponses=false 并重试（回退到 Completion）
    // 在 $useResponsesAPI 的 IFEQ 门后追加 OpenAiApiTypeSupport.allowResponsesFallback(this$0)
    // 检查：协议被固定（非 AUTO）时直接走原 throw 路径，不再回退。
    // 复用原 IFEQ 的跳转目标（已有栈帧），不产生新帧需求。
    static void patchCatchHandler(Path in, Path out) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, CATCH1)).accept(cn, 0);
        MethodNode ivs = findMethod(cn, "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
        JumpInsnNode gate = null;
        for (AbstractInsnNode n = ivs.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GETFIELD && n instanceof FieldInsnNode
                    && ((FieldInsnNode) n).owner.equals(CATCH1) && ((FieldInsnNode) n).name.equals("$useResponsesAPI")
                    && n.getNext() != null && n.getNext().getOpcode() == IFEQ) {
                gate = (JumpInsnNode) n.getNext();
                break;
            }
        }
        if (gate == null) throw new IllegalStateException("$useResponsesAPI IFEQ gate not found in invokeSuspend");
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new FieldInsnNode(GETFIELD, CATCH1, "this$0", "L" + MODEL_API + ";"));
        l.add(new MethodInsnNode(INVOKESTATIC, SUPPORT, "allowResponsesFallback", "(L" + MODEL_API + ";)Z", false));
        l.add(new JumpInsnNode(IFEQ, gate.label));
        ivs.instructions.insert(gate, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, CATCH1, cw.toByteArray());
        System.out.println("patched " + CATCH1);
    }

    // OpenAiModelApiProvider.computeState：new OpenAiModelApi(...) 之后
    // 从 providerSettings（局部变量9，RemoteProviderData）读取设置并写入。
    // LocalModelApiProvider 不补丁 —— 本地模型无此设置，字段保持默认 AUTO。
    static void patchProvider(Path in, Path out) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, PROVIDER)).accept(cn, 0);
        MethodNode cs = findMethod(cn, "computeState", "()Lcom/google/studiobot/agentsdk/models/ModelApiProviderState;");
        AbstractInsnNode ctorCall = findInvoke(cs, MODEL_API, "<init>", "(Lkotlin/Lazy;Lcom/google/studiobot/datamodel/models/ModelConfig;Lkotlin/jvm/functions/Function1;)V");
        InsnList l = new InsnList();
        l.add(new InsnNode(DUP));
        l.add(new VarInsnNode(ALOAD, 9));
        l.add(new MethodInsnNode(INVOKEVIRTUAL, RPD, "getOpenAiApiType", "()L" + OPENAI_TYPE + ";", false));
        l.add(new MethodInsnNode(INVOKEVIRTUAL, MODEL_API, "setOpenAiApiType", "(L" + OPENAI_TYPE + ";)V", false));
        cs.instructions.insert(ctorCall, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, PROVIDER, cw.toByteArray());
        System.out.println("patched " + PROVIDER);
    }

    // OpenAiResponsesApiV2.toInputItem(ModelChatMessage,...)：DeepSeek 思考模式要求
    // 每轮 assistant 消息回传 reasoning_text；模型有时不返回思考块就直接返回工具调用，
    // 原逻辑此时跳过 reasoning item，导致下一轮请求 400。
    // toolCalls 循环起点（getToolCalls 前的 aload_1）是三条路径的汇合点，且其前已有
    // 接受 Object 局部变量的 F_FULL 汇合帧：在此插入 OpenAiResponsesSupport.ensureReasoning，
    // 三条路径都会流经它，由它判断（模型匹配且无 thought/thoughtSignature）是否补占位思考。
    // 不改任何分支、不新增帧。
    static void patchResponsesApi(Path in, Path out) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, RESPONSES_V2)).accept(cn, 0);
        MethodNode m = findMethod(cn, "toInputItem", "(L" + CHAT_MESSAGE + ";Ljava/lang/String;Ljava/util/concurrent/atomic/AtomicInteger;)Ljava/util/List;");
        AbstractInsnNode getToolCalls = findInvoke(m, CHAT_MESSAGE, "getToolCalls", "()Ljava/util/List;");
        AbstractInsnNode aloadMsg = getToolCalls.getPrevious();
        if (aloadMsg == null || aloadMsg.getOpcode() != ALOAD || ((VarInsnNode) aloadMsg).var != 1) {
            throw new IllegalStateException("expected aload_1 before getToolCalls in toInputItem");
        }
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 5));
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new VarInsnNode(ALOAD, 3));
        l.add(new MethodInsnNode(INVOKESTATIC, RESPONSES_SUPPORT, "ensureReasoning",
                "(Ljava/util/List;L" + CHAT_MESSAGE + ";Ljava/lang/String;Ljava/util/concurrent/atomic/AtomicInteger;)V", false));
        m.instructions.insertBefore(aloadMsg, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, RESPONSES_V2, cw.toByteArray());
        System.out.println("patched " + RESPONSES_V2);
    }

    // OpenAiCompletionApiV2.toMessageParam(ModelChatMessage)：DeepSeek/Qwen 思考模式要求
    // 多轮回传 assistant 消息的 reasoning_content，原逻辑只发 content 与 tool_calls。
    // 方法末尾 return ofAssistant(builder.build())：getstatic Companion 之后、
    // aload_2（builder）之前插入 OpenAiCompletionSupport.attachReasoningContent，
    // thought 非空时经 builder.putAdditionalProperty 附加 reasoning_content。
    // 插入点非跳转目标、栈中仅 Companion，直线调用不改分支、不新增帧。
    static void patchCompletionApi(Path in, Path out) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, COMPLETION_V2)).accept(cn, 0);
        MethodNode m = findMethod(cn, "toMessageParam", "(L" + CHAT_MESSAGE + ";)L" + MSG_PARAM + ";");
        AbstractInsnNode build = findInvoke(m, ASSISTANT_BUILDER, "build",
                "()Lcom/openai/models/chat/completions/ChatCompletionAssistantMessageParam;");
        AbstractInsnNode aloadBuilder = build.getPrevious();
        if (aloadBuilder == null || aloadBuilder.getOpcode() != ALOAD || ((VarInsnNode) aloadBuilder).var != 2) {
            throw new IllegalStateException("expected aload_2 before Builder.build() in toMessageParam");
        }
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new MethodInsnNode(INVOKESTATIC, COMPLETION_SUPPORT, "attachReasoningContent",
                "(L" + ASSISTANT_BUILDER + ";L" + CHAT_MESSAGE + ";)V", false));
        m.instructions.insertBefore(aloadBuilder, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, COMPLETION_V2, cw.toByteArray());
        System.out.println("patched " + COMPLETION_V2);
    }
}
