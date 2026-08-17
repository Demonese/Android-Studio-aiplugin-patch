import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
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
    static final String CC_PARAMS_BUILDER = "com/openai/models/chat/completions/ChatCompletionCreateParams$Builder";
    static final String MSG_PARAM = "com/openai/models/chat/completions/ChatCompletionMessageParam";
    static final String CHAT_MESSAGE = "com/google/studiobot/datamodel/models/ModelChatMessage";
    static final String QUERY_BOX_KT = "com/google/studiobot/ui/querybox/QueryBoxKt";
    static final String THINKING_PICKER = "com/google/studiobot/ui/querybox/ThinkingEffortPicker";
    static final String MODEL_PICKER_KT = "com/google/studiobot/ui/trajectory/ModelPickerKt";
    static final String COMPOSER = "androidx/compose/runtime/Composer";
    static final String PMETA = "com/google/studiobot/agentsdk/conversations/PersistedMetadata";
    static final String PMETA_SER = PMETA + "$$serializer";
    static final String TLC = "com/google/studiobot/agentsdk/conversations/TopLevelConversation";
    static final String DC = "com/google/studiobot/agentsdk/conversations/DefaultConversation";
    static final String ORCH = "com/google/studiobot/controller/ActiveConversationOrchestrator";
    static final String TTC = "com/google/studiobot/controller/TrajectoryTimelineController";
    static final String EVENT_PRESENTED = "com/google/studiobot/ui/TrajectoryEvent$ConversationPresented";
    static final String STORE = "com/google/studiobot/ui/querybox/ThinkingEffortStore";
    static final String KX_DESC = "kotlinx/serialization/descriptors/SerialDescriptor";
    static final String KX_ENCODER = "kotlinx/serialization/encoding/CompositeEncoder";
    static final String KX_DECODER = "kotlinx/serialization/encoding/CompositeDecoder";
    static final String KX_STRING_SER = "kotlinx/serialization/internal/StringSerializer";
    static final String KX_SER_STRAT = "kotlinx/serialization/SerializationStrategy";
    static final String KX_DESER_STRAT = "kotlinx/serialization/DeserializationStrategy";
    static final String KX_PLUGIN_DESC = "kotlinx/serialization/internal/PluginGeneratedSerialDescriptor";

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        switch (cmd) {
            case "data": patchData(args[1], args[2]); break;
            case "panel": patchPanel(args[1], args[2]); break;
            case "api": patchApi(args[1], args[2]); break;
            case "querybox": patchQueryBox(args[1], args[2]); break;
            case "metadata": patchMetadata(args[1], args[2]); break;
            case "metaser": patchMetadataSerializer(args[1], args[2], args[3]); break;
            case "convmeta": patchPrepareMetadata(args[1], args[2]); break;
            case "orch": patchOrchestrator(args[1], args[2]); break;
            case "timeline": patchTimelineController(args[1], args[2]); break;
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

        // reasoning.effort：原逻辑仅当 thinkingConfig.thinkingLevel!=null 时发 reasoning，
        // 而 defaultForAgent 的 level 恒为 null → Responses 从不带 reasoning。
        // 在方法末尾 return paramsBuilder.build() 之前无条件覆写：
        // paramsBuilder.reasoning(Reasoning.builder().effort(ThinkingEffortStore.toOpenAiReasoningEffort()).build())
        // Builder 的 reasoning() 后写覆盖先写；includeThoughts=false 分支的 NONE 也被会话档位覆盖
        // （监督子请求与主请求同模型同档位，供应商不接受时由协议/参数自适应回退兜底）。
        MethodNode cp = findMethod(cn, "createParams", null);
        AbstractInsnNode ret = null;
        for (AbstractInsnNode n = cp.instructions.getLast(); n != null; n = n.getPrevious()) {
            if (n.getOpcode() == ARETURN) {
                ret = n;
                break;
            }
        }
        AbstractInsnNode buildCall = ret.getPrevious();
        AbstractInsnNode loadBuilder = buildCall.getPrevious();
        if (buildCall.getOpcode() != INVOKEVIRTUAL || loadBuilder.getOpcode() != ALOAD) {
            throw new IllegalStateException("expected paramsBuilder.build() before areturn in createParams");
        }
        InsnList rl = new InsnList();
        rl.add(new VarInsnNode(ALOAD, ((VarInsnNode) loadBuilder).var));
        rl.add(new FieldInsnNode(GETSTATIC, "com/openai/models/Reasoning", "Companion",
                "Lcom/openai/models/Reasoning$Companion;"));
        rl.add(new MethodInsnNode(INVOKEVIRTUAL, "com/openai/models/Reasoning$Companion", "builder",
                "()Lcom/openai/models/Reasoning$Builder;", false));
        rl.add(new MethodInsnNode(INVOKESTATIC, STORE, "toOpenAiReasoningEffort",
                "()Lcom/openai/models/ReasoningEffort;", false));
        rl.add(new MethodInsnNode(INVOKEVIRTUAL, "com/openai/models/Reasoning$Builder", "effort",
                "(Lcom/openai/models/ReasoningEffort;)Lcom/openai/models/Reasoning$Builder;", false));
        rl.add(new MethodInsnNode(INVOKEVIRTUAL, "com/openai/models/Reasoning$Builder", "build",
                "()Lcom/openai/models/Reasoning;", false));
        rl.add(new MethodInsnNode(INVOKEVIRTUAL, "com/openai/models/responses/ResponseCreateParams$Builder",
                "reasoning", "(Lcom/openai/models/Reasoning;)Lcom/openai/models/responses/ResponseCreateParams$Builder;", false));
        rl.add(new InsnNode(POP));
        cp.instructions.insertBefore(loadBuilder, rl);

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
    //
    // OpenAiCompletionApiV2.createParams：系统消息恒用 system role。
    // 原逻辑仅当 useSystemMessage（唯一调用方 OpenAiModelApi 硬编码 false）或
    // JVM 属性 studio.ml.openai.chat.sendAsSystemMessage=true 时用 addSystemMessage，
    // 否则 addDeveloperMessage；众多第三方兼容供应商不认 developer role 直接 400。
    // OpenAI 官方仍兼容 system role，故把 addDeveloperMessage 调用改写为 addSystemMessage。
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

        MethodNode cp = findMethod(cn, "createParams", null);
        AbstractInsnNode devCall = findInvoke(cp, CC_PARAMS_BUILDER, "addDeveloperMessage",
                "(Ljava/lang/String;)L" + CC_PARAMS_BUILDER + ";");
        cp.instructions.set(devCall, new MethodInsnNode(INVOKEVIRTUAL, CC_PARAMS_BUILDER, "addSystemMessage",
                "(Ljava/lang/String;)L" + CC_PARAMS_BUILDER + ";", false));

        // reasoning_effort：原逻辑 INSTANCE.toReasoningEffort(thinkingConfig.getThinkingLevel())
        // 只能表达 ThinkingLevel 的 low/medium/high（defaultForAgent 的 level 恒为 null → 固定 MEDIUM）。
        // 替换为 ThinkingEffortStore.toOpenAiReasoningEffort()（会话级下拉，7 档全保真）。
        // 原守卫（thinkingConfig!=null && includeThoughts==true && !omitReasoningEffort）不变，
        // supportReasoningEffort 自适应回退仍然有效。
        // 字节码序列 getstatic INSTANCE / aload level / getThinkingLevel / invokespecial toReasoningEffort
        // 整体替换为一条 INVOKESTATIC：栈上仅 [builder]，进出栈深度一致。
        AbstractInsnNode toRe = findInvoke(cp, COMPLETION_V2, "toReasoningEffort",
                "(Lcom/google/studiobot/datamodel/models/ThinkingLevel;)Lcom/openai/models/ReasoningEffort;");
        AbstractInsnNode getLevel = toRe.getPrevious();
        AbstractInsnNode loadLevel = getLevel.getPrevious();
        AbstractInsnNode getInstance = loadLevel.getPrevious();
        if (getLevel.getOpcode() != INVOKEVIRTUAL || loadLevel.getOpcode() != ALOAD
                || getInstance.getOpcode() != GETSTATIC) {
            throw new IllegalStateException("unexpected sequence before toReasoningEffort in createParams");
        }
        cp.instructions.insertBefore(getInstance, new MethodInsnNode(INVOKESTATIC, STORE,
                "toOpenAiReasoningEffort", "()Lcom/openai/models/ReasoningEffort;", false));
        cp.instructions.remove(getInstance);
        cp.instructions.remove(loadLevel);
        cp.instructions.remove(getLevel);
        cp.instructions.remove(toRe);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, COMPLETION_V2, cw.toByteArray());
        System.out.println("patched " + COMPLETION_V2);
    }

    // QueryBoxKt.ActionsRow：在模型选择下拉（ModelPicker）与其后的 8dp Spacer 之后，
    // 插入 ThinkingEffortPicker.render(composer) + 8dp Spacer，
    // 即位于模型选择与发送按钮之间且两侧间距一致。
    // 锚点：INVOKESTATIC ModelPickerKt.ModelPicker 之后第一个 INVOKESTATIC SpacerKt.Spacer；
    // Spacer 的 composer 实参是其前第二条指令 ALOAD（前一条为 bipush 6）。
    // 插入序列栈深度复原、无新分支、无需新帧；宽度经 Dp.constructor-impl 内联类构造。
    static void patchQueryBox(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, QUERY_BOX_KT)).accept(cn, 0);
        MethodNode m = findMethod(cn, "ActionsRow", null);
        AbstractInsnNode picker = findInvoke(m, MODEL_PICKER_KT, "ModelPicker",
                "(Lcom/google/studiobot/ui/ModelPickerUiState;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;Landroidx/compose/ui/Modifier;L" + COMPOSER + ";II)V");
        AbstractInsnNode spacer = picker.getNext();
        while (spacer != null && !(spacer.getType() == AbstractInsnNode.METHOD_INSN
                && ((MethodInsnNode) spacer).owner.equals("androidx/compose/foundation/layout/SpacerKt")
                && ((MethodInsnNode) spacer).name.equals("Spacer"))) {
            spacer = spacer.getNext();
        }
        if (spacer == null) {
            throw new IllegalStateException("Spacer after ModelPicker not found in ActionsRow");
        }
        AbstractInsnNode push6 = spacer.getPrevious();
        AbstractInsnNode aload = push6 == null ? null : push6.getPrevious();
        if (push6 == null || push6.getOpcode() != BIPUSH || ((IntInsnNode) push6).operand != 6
                || aload == null || aload.getOpcode() != ALOAD) {
            throw new IllegalStateException("unexpected instruction sequence before Spacer in ActionsRow");
        }
        int composerVar = ((VarInsnNode) aload).var;
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, composerVar));
        l.add(new MethodInsnNode(INVOKESTATIC, THINKING_PICKER, "render", "(L" + COMPOSER + ";)V", false));
        l.add(new FieldInsnNode(GETSTATIC, "androidx/compose/ui/Modifier", "Companion",
                "Landroidx/compose/ui/Modifier$Companion;"));
        l.add(new TypeInsnNode(CHECKCAST, "androidx/compose/ui/Modifier"));
        l.add(new IntInsnNode(BIPUSH, 8));
        l.add(new InsnNode(I2F));
        l.add(new MethodInsnNode(INVOKESTATIC, "androidx/compose/ui/unit/Dp", "constructor-impl", "(F)F", false));
        l.add(new MethodInsnNode(INVOKESTATIC, "androidx/compose/foundation/layout/SizeKt", "width-3ABfNKs",
                "(Landroidx/compose/ui/Modifier;F)Landroidx/compose/ui/Modifier;", false));
        l.add(new VarInsnNode(ALOAD, composerVar));
        l.add(new IntInsnNode(BIPUSH, 6));
        l.add(new MethodInsnNode(INVOKESTATIC, "androidx/compose/foundation/layout/SpacerKt", "Spacer",
                "(Landroidx/compose/ui/Modifier;L" + COMPOSER + ";I)V", false));
        m.instructions.insert(spacer, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, QUERY_BOX_KT, cw.toByteArray());
        System.out.println("patched " + QUERY_BOX_KT);
    }

    // ===== 思考强度持久化（metadata.json 增加 reasoningEffort 字段）=====

    // COMPUTE_FRAMES 写入器：$$serializer 新增 switch 分支目标需重算栈映射帧，
    // getCommonSuperClass 从给定 classpath 加载类。
    static ClassWriter framesWriter(String classpath) {
        List<URL> urls = new ArrayList<>();
        for (String p : classpath.split(File.pathSeparator)) {
            try {
                urls.add(new File(p).toURI().toURL());
            } catch (Exception e) {
                // 忽略无效条目
            }
        }
        final URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                    Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                    if (c1.isAssignableFrom(c2)) return type1;
                    if (c2.isAssignableFrom(c1)) return type2;
                    if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                    do {
                        c1 = c1.getSuperclass();
                    } while (!c1.isAssignableFrom(c2));
                    return c1.getName().replace('.', '/');
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
    }

    // PersistedMetadata：加 reasoningEffort 字段与 getter/setter；
    // write$Self 末尾直线调用 ThinkingEffortStore.encodeElement 写出元素 12。
    static void patchMetadata(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, PMETA)).accept(cn, 0);
        cn.visitField(ACC_PRIVATE, "reasoningEffort", "Ljava/lang/String;", null, null);
        MethodNode g = new MethodNode(ACC_PUBLIC | ACC_FINAL, "getReasoningEffort", "()Ljava/lang/String;", null, null);
        g.instructions.add(new VarInsnNode(ALOAD, 0));
        g.instructions.add(new FieldInsnNode(GETFIELD, PMETA, "reasoningEffort", "Ljava/lang/String;"));
        g.instructions.add(new InsnNode(ARETURN));
        cn.methods.add(g);
        MethodNode s = new MethodNode(ACC_PUBLIC | ACC_FINAL, "setReasoningEffort", "(Ljava/lang/String;)V", null, null);
        s.instructions.add(new VarInsnNode(ALOAD, 0));
        s.instructions.add(new VarInsnNode(ALOAD, 1));
        s.instructions.add(new FieldInsnNode(PUTFIELD, PMETA, "reasoningEffort", "Ljava/lang/String;"));
        s.instructions.add(new InsnNode(RETURN));
        cn.methods.add(s);

        MethodNode w = findMethod(cn, "write$Self$aiplugin_v2_agent_sdk", null);
        AbstractInsnNode ret = w.instructions.getLast();
        while (ret != null && ret.getOpcode() != RETURN) ret = ret.getPrevious();
        if (ret == null) throw new IllegalStateException("no RETURN at end of write$Self");
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 0));
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new MethodInsnNode(INVOKESTATIC, STORE, "encodeElement",
                "(L" + PMETA + ";L" + KX_ENCODER + ";L" + KX_DESC + ";)V", false));
        w.instructions.insertBefore(ret, l);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, PMETA, cw.toByteArray());
        System.out.println("patched " + PMETA);
    }

    // 元素 12 的解码 + seen0 置位 4096（存入局部变量 23）。
    // 注意 decodeElement 只需 (input, desc) 两参，索引 12 在其内部。
    static AbstractInsnNode prevReal(AbstractInsnNode n) {
        while (n != null && (n instanceof LineNumberNode || n instanceof FrameNode)) n = n.getPrevious();
        return n;
    }

    static InsnList decode12WithSeen() {
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 21));
        l.add(new VarInsnNode(ALOAD, 2));
        l.add(new MethodInsnNode(INVOKESTATIC, STORE, "decodeElement",
                "(L" + KX_DECODER + ";L" + KX_DESC + ";)Ljava/lang/String;", false));
        l.add(new VarInsnNode(ASTORE, 23));
        l.add(new VarInsnNode(ILOAD, 5));
        l.add(new IntInsnNode(SIPUSH, 4096));
        l.add(new InsnNode(IOR));
        l.add(new VarInsnNode(ISTORE, 5));
        return l;
    }

    // PersistedMetadata$$serializer：
    //  - <clinit>：descriptor 容量 12->13，追加 addElement("reasoningEffort", true)
    //  - childSerializers()：数组 12->13
    //  - deserialize：开头初始化局部 23；顺序路径与 tableswitch 各加元素 12 读取；
    //    末尾构造后回填字段并调 ThinkingEffortStore.onLoaded
    static void patchMetadataSerializer(String inDir, String outDir, String classpath) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, PMETA_SER)).accept(cn, 0);

        MethodNode cl = findMethod(cn, "<clinit>", null);
        AbstractInsnNode dgInit = findInvoke(cl, KX_PLUGIN_DESC, "<init>",
                "(Ljava/lang/String;Lkotlinx/serialization/internal/GeneratedSerializer;I)V");
        AbstractInsnNode cap = prevReal(dgInit.getPrevious());
        if (cap == null || cap.getOpcode() != BIPUSH || ((IntInsnNode) cap).operand != 12) {
            throw new IllegalStateException("expected bipush 12 before PluginGeneratedSerialDescriptor.<init>");
        }
        ((IntInsnNode) cap).operand = 13;
        AbstractInsnNode put = null;
        for (AbstractInsnNode n = cl.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == PUTSTATIC && ((FieldInsnNode) n).owner.equals(PMETA_SER)
                    && ((FieldInsnNode) n).name.equals("descriptor")) {
                put = n;
                break;
            }
        }
        if (put == null) throw new IllegalStateException("descriptor putstatic not found in <clinit>");
        AbstractInsnNode cc = prevReal(put.getPrevious());
        if (cc == null || cc.getOpcode() != CHECKCAST) throw new IllegalStateException("unexpected clinit tail");
        InsnList addEl = new InsnList();
        addEl.add(new VarInsnNode(ALOAD, 0));
        addEl.add(new LdcInsnNode("reasoningEffort"));
        addEl.add(new InsnNode(ICONST_1));
        addEl.add(new MethodInsnNode(INVOKEVIRTUAL, KX_PLUGIN_DESC, "addElement", "(Ljava/lang/String;Z)V", false));
        cl.instructions.insertBefore(cc, addEl);

        MethodNode cs = findMethod(cn, "childSerializers", null);
        boolean sized = false;
        for (AbstractInsnNode n = cs.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == ANEWARRAY && ((TypeInsnNode) n).desc.equals("kotlinx/serialization/KSerializer")) {
                AbstractInsnNode push = prevReal(n.getPrevious());
                if (push != null && push.getOpcode() == BIPUSH && ((IntInsnNode) push).operand == 12) {
                    ((IntInsnNode) push).operand = 13;
                    sized = true;
                    break;
                }
            }
        }
        if (!sized) throw new IllegalStateException("childSerializers array size not found");

        MethodNode d = findMethod(cn, "deserialize", null);

        AbstractInsnNode begin = findInvoke(d, "kotlinx/serialization/encoding/Decoder", "beginStructure",
                "(L" + KX_DESC + ";)L" + KX_DECODER + ";");
        AbstractInsnNode p = prevReal(begin.getPrevious());
        if (p == null || p.getOpcode() != ALOAD || ((VarInsnNode) p).var != 2) {
            throw new IllegalStateException("unexpected beginStructure context");
        }
        p = prevReal(p.getPrevious());
        if (p == null || p.getOpcode() != ALOAD || ((VarInsnNode) p).var != 1) {
            throw new IllegalStateException("unexpected beginStructure context");
        }
        p = prevReal(p.getPrevious());
        if (p == null || p.getOpcode() != ASTORE || ((VarInsnNode) p).var != 20) {
            throw new IllegalStateException("unexpected beginStructure context");
        }
        InsnList init = new InsnList();
        init.add(new InsnNode(ACONST_NULL));
        init.add(new VarInsnNode(ASTORE, 23));
        d.instructions.insert(p, init);

        AbstractInsnNode endStruct = findInvoke(d, KX_DECODER, "endStructure", "(L" + KX_DESC + ";)V");
        AbstractInsnNode arg1 = prevReal(endStruct.getPrevious());
        if (arg1 == null || arg1.getOpcode() != ALOAD) {
            throw new IllegalStateException("unexpected endStructure context");
        }
        AbstractInsnNode arg0 = prevReal(arg1.getPrevious());
        if (arg0 == null || arg0.getOpcode() != ALOAD) {
            throw new IllegalStateException("unexpected endStructure context");
        }
        LabelNode tailLabel = null;
        for (AbstractInsnNode n = arg0.getPrevious(); n != null; n = n.getPrevious()) {
            if (n instanceof LabelNode) {
                tailLabel = (LabelNode) n;
                break;
            }
            if (!(n instanceof LineNumberNode) && !(n instanceof FrameNode)) break;
        }
        if (tailLabel == null) throw new IllegalStateException("tail label not found");

        JumpInsnNode seqGoto = null;
        for (AbstractInsnNode n = d.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GOTO && ((JumpInsnNode) n).label == tailLabel) {
                seqGoto = (JumpInsnNode) n;
                break;
            }
        }
        if (seqGoto == null) throw new IllegalStateException("sequential goto tail not found");
        d.instructions.insertBefore(seqGoto, decode12WithSeen());

        TableSwitchInsnNode table = null;
        for (AbstractInsnNode n = d.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof TableSwitchInsnNode) {
                table = (TableSwitchInsnNode) n;
                break;
            }
        }
        if (table == null) throw new IllegalStateException("tableswitch not found");
        LabelNode loopHead = null;
        for (AbstractInsnNode n = d.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GOTO) {
                LabelNode lab = ((JumpInsnNode) n).label;
                AbstractInsnNode t = lab.getNext();
                while (t != null && (t instanceof LineNumberNode || t instanceof FrameNode)) t = t.getNext();
                if (t != null && t.getOpcode() == ILOAD && ((VarInsnNode) t).var == 3) {
                    loopHead = lab;
                    break;
                }
            }
        }
        if (loopHead == null) throw new IllegalStateException("loop head label not found");
        AbstractInsnNode unk = null;
        for (AbstractInsnNode n = table; n != null; n = n.getNext()) {
            if (n.getOpcode() == NEW && ((TypeInsnNode) n).desc.equals("kotlinx/serialization/UnknownFieldException")) {
                unk = n;
                break;
            }
        }
        if (unk == null) throw new IllegalStateException("UnknownFieldException handler not found");
        LabelNode case12 = new LabelNode();
        InsnList c12 = new InsnList();
        c12.add(case12);
        c12.add(decode12WithSeen());
        c12.add(new JumpInsnNode(GOTO, loopHead));
        // 插到 default 标签之前：default 分支仍直达 UnknownFieldException，case12 仅经 switch 进入
        d.instructions.insertBefore(table.dflt, c12);
        if (table.max != 11) throw new IllegalStateException("unexpected tableswitch max " + table.max);
        table.max = 12;
        table.labels.add(case12);

        AbstractInsnNode ctor = null;
        for (AbstractInsnNode n = d.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) n).owner.equals(PMETA)
                    && ((MethodInsnNode) n).name.equals("<init>")
                    && ((MethodInsnNode) n).desc.endsWith("Lkotlinx/serialization/internal/SerializationConstructorMarker;)V")) {
                ctor = n;
                break;
            }
        }
        if (ctor == null) throw new IllegalStateException("serialization ctor not found");
        InsnList tail = new InsnList();
        tail.add(new InsnNode(DUP));
        tail.add(new VarInsnNode(ALOAD, 23));
        tail.add(new MethodInsnNode(INVOKEVIRTUAL, PMETA, "setReasoningEffort", "(Ljava/lang/String;)V", false));
        tail.add(new VarInsnNode(ALOAD, 7));
        tail.add(new VarInsnNode(ALOAD, 23));
        tail.add(new MethodInsnNode(INVOKESTATIC, STORE, "onLoaded", "(Ljava/lang/String;Ljava/lang/String;)V", false));
        d.instructions.insert(ctor, tail);

        ClassWriter cw = framesWriter(classpath);
        cn.accept(cw);
        writeClass(out, PMETA_SER, cw.toByteArray());
        System.out.println("patched " + PMETA_SER);
    }

    // TopLevelConversation/DefaultConversation.prepareMetadata：
    // 构造 PersistedMetadata 后 dup + ThinkingEffortStore.applyTo 回填 reasoningEffort。
    static void patchPrepareMetadata(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        for (String cls : new String[]{TLC, DC}) {
            ClassNode cn = new ClassNode();
            new ClassReader(readClass(in, cls)).accept(cn, 0);
            MethodNode m = findMethod(cn, "prepareMetadata", null);
            AbstractInsnNode ctor = null;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) n).owner.equals(PMETA)
                        && ((MethodInsnNode) n).name.equals("<init>")) {
                    ctor = n;
                    break;
                }
            }
            if (ctor == null) throw new IllegalStateException("PersistedMetadata ctor not found in prepareMetadata of " + cls);
            InsnList l = new InsnList();
            l.add(new InsnNode(DUP));
            l.add(new MethodInsnNode(INVOKESTATIC, STORE, "applyTo", "(L" + PMETA + ";)V", false));
            m.instructions.insert(ctor, l);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            writeClass(out, cls, cw.toByteArray());
            System.out.println("patched " + cls);
        }
    }

    // ActiveConversationOrchestrator.selectConversation：入口通知 ThinkingEffortStore 刷新。
    static void patchOrchestrator(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, ORCH)).accept(cn, 0);
        MethodNode m = findMethod(cn, "selectConversation", null);
        AbstractInsnNode check = findInvoke(m, "kotlin/jvm/internal/Intrinsics", "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V");
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new MethodInsnNode(INVOKESTATIC, STORE, "onConversationSelection",
                "(Lcom/google/studiobot/controller/ConversationSelection;)V", false));
        m.instructions.insert(check, l);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, ORCH, cw.toByteArray());
        System.out.println("patched " + ORCH);
    }

    // TrajectoryTimelineController.handleEvent：ConversationPresented 分支
    // （clearStatus 调用之后）追加 ThinkingEffortStore.onConversationPresented，
    // 覆盖 IDE 重启后首个会话的下拉同步（初始选择不经 selectConversation）。
    static void patchTimelineController(String inDir, String outDir) throws Exception {
        Path in = Path.of(inDir);
        Path out = Path.of(outDir);
        ClassNode cn = new ClassNode();
        new ClassReader(readClass(in, TTC)).accept(cn, 0);
        MethodNode m = findMethod(cn, "handleEvent", null);
        AbstractInsnNode clear = findInvoke(m, "com/google/studiobot/controller/ConversationStatusCheckinService",
                "clearStatus", "(Ljava/lang/String;)V");
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ALOAD, 1));
        l.add(new TypeInsnNode(CHECKCAST, EVENT_PRESENTED));
        l.add(new MethodInsnNode(INVOKEVIRTUAL, EVENT_PRESENTED, "getConversationId", "()Ljava/lang/String;", false));
        l.add(new MethodInsnNode(INVOKESTATIC, STORE, "onConversationPresented", "(Ljava/lang/String;)V", false));
        m.instructions.insert(clear, l);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        writeClass(out, TTC, cw.toByteArray());
        System.out.println("patched " + TTC);
    }
}
