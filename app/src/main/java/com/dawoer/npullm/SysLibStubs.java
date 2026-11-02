package com.dawoer.npullm;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * vc118 批1（A17 热修）：stub 目录追加「指向系统库」的 symlink。
 *
 * 根因（8T A17 分析稿 §2，1015 定案）：exec 子进程的 bionic 按 soname 去重，
 * LD_LIBRARY_PATH 前置目录里的捆绑旧库抢先占位 → A17 系统库需求的新符号
 * （libc++ __hash_memory / libdmabufheap 3 参 Alloc）悬空 → CANNOT LINK。
 * 修法 = stub 目录放 <lib> -> /system/lib64/<lib> 的 symlink，目录置于
 * LD_LIBRARY_PATH 最前，系统新库按 soname 胜出。qwerkilo 已真机验证该方向。
 *
 * 纯 static 工具：无实例、无匿名类、无内部类（d8 纪律）。零 android.* 依赖，
 * JVM 逻辑测试（main/java 路径）可直接对真实临时目录跑构造/幂等/降级用例。
 */
final class SysLibStubs {

    private SysLibStubs() { } // no instances

    /**
     * 对每个 lib：目标 <systemLibDir>/<lib> 不存在 → 跳过并计数（降级，
     * 不建死链）；否则 delete 旧链接 + createSymbolicLink(stubDir/<lib> →
     * 目标)，幂等（重复调用=重建同链）。逐条失败抛至调用方的 catch；
     * 3 参重载的日志走 System.err 兜底镜像。
     *
     * @return 成功建立的 symlink 数（跳过不计成功）
     */
    static int ensure(File stubDir, String[] libs, String systemLibDir) {
        return ensure(stubDir, libs, systemLibDir, null);
    }

    /** 4 参重载：okOut 非空时逐条收集动作行（由调用方透传引擎日志页）。 */
    static int ensure(File stubDir, String[] libs, String systemLibDir, List<String> okOut) {
        int made = 0;
        for (String lib : libs) {
            File target = new File(systemLibDir, lib);
            if (!target.canRead()) {
                String skip = "[syslib-stub] 跳过 " + lib + "（" + systemLibDir
                        + " 下不存在，不建死链；维持原库解析路径）";
                System.err.println(skip);
                if (okOut != null) okOut.add(skip);
                continue;
            }
            try {
                stubDir.mkdirs();
                File link = new File(stubDir, lib);
                link.delete(); // 幂等：旧链接/旧文件一律刷新重建（nativeDir 可随重装变化）
                Files.createSymbolicLink(link.toPath(), target.toPath());
                String ok = "[syslib-stub] " + lib + " -> " + target.getPath();
                System.err.println(ok);
                if (okOut != null) okOut.add(ok);
                made++;
            } catch (Throwable t) {
                // 逐条失败写日志并继续（部分失败计数语义），不抛出、不中断后续 lib
                String bad = "[syslib-stub] " + lib + " symlink 失败（该库维持原解析路径）: " + t;
                System.err.println(bad);
                if (okOut != null) okOut.add(bad);
            }
        }
        return made;
    }
}
