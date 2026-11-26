package com.beichen.polaris.startup

import android.content.Context
import com.beichen.polaris.core.AgentLogger

/**
 * 启动自救（2026-11-26 真机事故）。
 *
 * ## 它解决的是哪一类问题
 *
 * 事故现场：生成图片时闪退，之后**每次启动都崩**，清缓存无效、强制停止无效，
 * 只有「清除数据」能进——而清除数据会把会话和崩溃证据一起删掉。
 *
 * 这类故障的共同点是「某份持久化状态坏了，启动时读它就崩」，用户**自己没有出口**：
 * - 清缓存没用：坏文件在 `filesDir`，不在 `cacheDir`，两个目录不相干；
 * - 强停没用：下次启动还读同一份；
 * - 清数据有用，但代价是丢掉排障最需要的两样东西。
 *
 * ## 判据：不猜「谁坏了」，只数「几次没起来」
 *
 * 不去解析业务状态——那等于要求「在崩溃点之前就知道哪里会崩」，循环论证。
 * 只记两个事实：**这次启动开始了**（[begin]）、**界面组合成功了**（[markBooted]）。
 * 中间断掉就是一次启动失败。连续 [StartupGuardPolicy.MAX_STRIKES] 次失败 →
 * 把可能带病的数据**移走**（[StartupQuarantine]）。
 *
 * 用「数失败次数」而不是「认文件」还有一个好处：图片只是**触发器**，不是原因。
 * 真正坏掉的可能是一条会话、一份运行态、或者一个草稿。判据不认具体文件，
 * 所以下一次以别的形式复发时，这道兜底照样生效。
 *
 * ## 边界
 *
 * 只管 `onCreate` 到「根组合成功」这一段。首帧之后再崩（后台任务、绘制回调）
 * 不计入——那种情况用户看得见界面，不构成「被锁在门外」。
 *
 * 因此 [markBooted] 的调用点必须在**组合成功之后**：Compose 的 `LaunchedEffect`
 * 在整棵树组合并应用变更后才启动，子组件抛异常时它不会执行，正好是这个语义。
 *
 * ## 本文件只做「读盘 + 记账 + 记日志」
 *
 * 判据在 [StartupGuardPolicy]、搬移在 [StartupQuarantine]，两者都不依赖 Android，
 * 因此 `scripts/qa/StartupGuardVerification.kt` 能真跑。
 * 这不是分层洁癖：这套逻辑只在用户已经被锁在门外时执行，没机会在开发机上试错。
 */
object StartupGuard {

    private const val SP_NAME = "startup_guard"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_BOOTED = "booted"
    private const val KEY_STRIKES = "strikes"
    private const val KEY_LAST_QUARANTINE = "last_quarantine"

    /** 本次 [begin] 的结论，供日志页/调用方展示。 */
    data class Report(
        /** 本次启动前累计的连续失败次数（含这一次）。 */
        val strikes: Int,
        /** 本次是否执行了隔离。 */
        val quarantined: Boolean,
        /** 隔离目录（未隔离时为 null）。 */
        val dir: java.io.File?,
    ) {
        val handled: Boolean get() = quarantined
    }

    /**
     * 启动最早期调用（`Application.onCreate` 里，早于一切业务初始化）。
     *
     * 用 `commit()` 而不是 `apply()`：进程随时可能在这之后崩掉，
     * 异步写有可能还没落盘就没了，那这次「尝试」就白记了。
     */
    fun begin(context: Context): Report {
        val sp = runCatching {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return Report(0, false, null)

        val now = System.currentTimeMillis()
        val strikes = StartupGuardPolicy.nextStrikes(
            prev = sp.getInt(KEY_STRIKES, 0),
            attemptAt = sp.getLong(KEY_ATTEMPT_AT, 0L),
            booted = sp.getBoolean(KEY_BOOTED, false),
            now = now,
        )

        runCatching {
            sp.edit()
                .putLong(KEY_ATTEMPT_AT, now)
                .putBoolean(KEY_BOOTED, false)
                .putInt(KEY_STRIKES, strikes)
                .commit()
        }

        if (!StartupGuardPolicy.shouldQuarantine(strikes)) {
            if (strikes > 0) {
                AgentLogger.w("Startup", "启动未完成第 $strikes 次；再失败一次将隔离状态")
            }
            return Report(strikes, false, null)
        }

        val result = runCatching { StartupQuarantine.move(context.filesDir, now) }.getOrNull()
        // 只有真搬走了东西才清计数。什么都没搬（目录建不出来 / 本来就没有状态）
        // 时清零会把计数打回 0，用户永远等不到下一次自救。
        runCatching {
            sp.edit()
                .putLong(KEY_LAST_QUARANTINE, now)
                .putInt(KEY_STRIKES, if (result != null) 0 else strikes)
                .commit()
        }
        if (result != null) {
            AgentLogger.e(
                "Startup",
                "连续 $strikes 次启动失败，已隔离 ${result.moved.size} 项到 ${result.dir.name}（未删除）"
            )
        } else {
            AgentLogger.e("Startup", "连续 $strikes 次启动失败，但隔离没搬走任何东西")
        }
        return Report(strikes, result != null, result?.dir)
    }

    /**
     * 根组合成功后调用：本次启动算成功，清计数。
     *
     * 只清计数、不删 attempt——保留时间戳，下次启动据此判断「间隔多久」。
     */
    fun markBooted(context: Context) {
        runCatching {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BOOTED, true)
                .putInt(KEY_STRIKES, 0)
                .apply()
        }
    }

    /** 最近一次隔离的时间戳（0 = 从未隔离）。启动完成后可据此给用户一句提示。 */
    fun lastQuarantineAt(context: Context): Long = runCatching {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_QUARANTINE, 0L)
    }.getOrDefault(0L)
}
