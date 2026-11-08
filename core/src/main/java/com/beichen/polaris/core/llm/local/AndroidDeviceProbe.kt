package com.beichen.polaris.core.llm.local

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import com.beichen.polaris.core.AgentLogger

/**
 * 采集设备画像（Android 侧）。所有判定逻辑在 [LocalDeviceRules] 里，本文件只负责「取数」。
 *
 * 三个来源各有各的缺口，所以三路都采、由规则层兜底：
 *  1. 两个 SoC 字段（SOC_MODEL / SOC_MANUFACTURER）：API 31（Android 12）才有。
 *     **偏偏 8 Gen 1 以下的老机型大多在 Android 11 及以下**，正是需要适配的那批人。
 *  2. `Build.HARDWARE`：一直都有，但值是 `qcom` / `mt6893` 这种，需要解析。
 *  3. `GL_RENDERER`：所有版本都能拿到，且能直接区分 Adreno / Mali。
 *     代价是要起一个 EGL 上下文——在少数阉割 ROM 上会失败，失败就当取不到，不重试。
 *
 * 采集一次缓存住：EGL 上下文创建是毫秒级但会占显存，不能每轮对话问一次。
 */
object AndroidDeviceProbe {

    private const val TAG = "DeviceProbe"

    @Volatile
    private var cached: LocalDeviceProfile? = null

    fun profile(context: Context): LocalDeviceProfile {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = collect(context.applicationContext)
            cached = p
            AgentLogger.i(TAG, "设备画像：${LocalDeviceRules.summarize(LocalDeviceRules.advise(p))}")
            return p
        }
    }

    /** 测试与「设置页手动刷新」用。 */
    fun invalidate() {
        cached = null
    }

    /**
     * 让下一次 [profile] 重新采集（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 直接委托 [invalidate]：这两个动作语义完全一样，各写一份只会在将来某次
     * 采集逻辑改动时忘掉其中一处。
     */
    fun reset() = invalidate()

    /**
     * 已采集的画像；启动阶段的采集还没跑完时为 null。
     *
     * 给不方便拿 Context 的调用方用（例如 [LocalLlamaClient] 只持有 setting 与 modelStore）。
     * 拿到 null 的调用方必须按「最保守」处理，不能反过来猜设备能力。
     */
    val cachedProfile: LocalDeviceProfile?
        get() = cached

    private fun collect(context: Context): LocalDeviceProfile {
        val socModel = readBuildField("SOC_MODEL")
        val socManu = readBuildField("SOC_MANUFACTURER")
        return LocalDeviceProfile(
            socModel = socModel,
            socManufacturer = socManu,
            hardware = Build.HARDWARE,
            glRenderer = queryGlRenderer(),
            sdkInt = Build.VERSION.SDK_INT,
            totalRamMb = totalRamMb(context),
            vulkanVersion = readVulkanVersion(),
        )
    }

    /**
     * 系统报告的 Vulkan 版本（"1.3" / "1.2" / "1.1" / null = 不支持）。
     *
     * 取法：native 里 `dlopen("libvulkan.so")` + `vkEnumerateInstanceVersion`，
     * 拿到的是 VK_MAKE_VERSION 打包值，这里只做解码。
     *
     * 为什么不用 `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`：那个
     * `FeatureInfo.version` 是 Int，且不同 Android 版本对它的编码不统一（打包值 /
     * 十进制 / 主次拼数都出现过）。按字符串读会编不过，按某一种编码读会静默错判——
     * 而错判的后果是「判成可用，点开一进初始化就抛」。
     *
     * 直接问 loader 还有一个好处：它与 ggml-vulkan 内部的判据是同一个量。
     */
    private fun readVulkanVersion(): String? =
        LocalDeviceRules.formatVulkanVersion(LlamaJni.vulkanApiVersion)

    /**
     * `SOC_MODEL` / `SOC_MANUFACTURER` 这两个 Build 字段是 API 31 才加的。
     *
     * 这里走反射而不是直接按名引用，有两个原因：
     *  1. 直接引用会让 Lint 报 NewApi，而我们的 minSdk 是 26；
     *  2. 老系统上直接引用是 `NoSuchFieldError`（Error 而非 Exception），
     *     很容易被上层的 `catch (e: Exception)` 漏掉。
     * 反射读不到就是 null，交给规则层按 HARDWARE / GPU 兜底。
     */
    private fun readBuildField(name: String): String? = try {
        val f = Build::class.java.getField(name)
        (f.get(null) as? String)?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
    } catch (t: Throwable) {
        null
    }

    /**
     * 总内存。读 /proc/meminfo 而不是 ActivityManager.MemoryInfo.totalMem，
     * 因为后者在某些 ROM 上返回的是「可用」而非「总量」（差一倍以上，会把 8GB 判成 4GB）。
     */
    private fun totalRamMb(context: Context): Int {
        val fromProc = try {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.div(1024L)
                    ?.toInt()
            }
        } catch (t: Throwable) {
            null
        }
        if (fromProc != null && fromProc > 0) return fromProc
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024L * 1024L)).toInt()
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 起一个 1x1 的 pbuffer 只为了问一句 GL_RENDERER，问完立刻拆干净。
     *
     * 每一步都可能失败（厂商驱动裁过 EGL 配置、没有 pbuffer 支持、GLES2 上下文建不起来），
     * 任何一步失败都返回 null 让规则层退到保守判断——**不要在这里抛异常**：
     * 设备探测失败不该让应用启动失败。
     */
    private fun queryGlRenderer(): String? {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfig, 0)) return null
            val config = configs[0] ?: return null

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return null

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            GLES20.glGetString(GLES20.GL_RENDERER)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            AgentLogger.w(TAG, "GL_RENDERER 探测失败：${t.message}")
            null
        } finally {
            // 拆的顺序固定：先解绑，再销毁 surface/context，最后 terminate。顺序错了会泄漏。
            // 先落成局部 val：var 被闭包捕获后 Kotlin 不再做智能转换，直接判空会编译不过。
            val d = display
            val c = context
            val s = surface
            runCatching {
                if (d != null && d != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s)
                    if (c != null && c != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, c)
                    EGL14.eglTerminate(d)
                }
            }
        }
    }
}
