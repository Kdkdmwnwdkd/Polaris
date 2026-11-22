package com.beichen.polaris.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音入口分支判定的判据（穷举三条输入的全部 8 种组合）。
 *
 * 8 种组合全写出来是故意的：这个函数只有三层 `when`，少写一条就等于把某类用户
 * 送进死路，而这类死路在真机上只表现为「点了没反应」——没有崩溃、没有日志。
 */
class VoiceInputDecisionTest {

    private fun next(installed: Boolean, granted: Boolean, asked: Boolean) =
        VoiceInputDecision.next(installed, granted, asked)

    /** V1：模型没装时，无论授权与否都先去下模型（要权限没用）。 */
    @Test
    fun `V1 模型未装优先于授权判定`() {
        assertEquals(VoiceInputNext.TellInstallModel, next(installed = false, granted = false, asked = false))
        assertEquals(VoiceInputNext.TellInstallModel, next(installed = false, granted = true, asked = false))
        assertEquals(VoiceInputNext.TellInstallModel, next(installed = false, granted = false, asked = true))
    }

    /** V2：已授权就直接开面板——哪怕之前问过一次（用户可能刚去设置里手开）。 */
    @Test
    fun `V2 已授权优先于问过没`() {
        assertEquals(VoiceInputNext.OpenPanel, next(installed = true, granted = true, asked = false))
        assertEquals(VoiceInputNext.OpenPanel, next(installed = true, granted = true, asked = true))
    }

    /** V3：没授权且没问过 → 弹一次系统框。 */
    @Test
    fun `V3 未授权且未问过则弹系统框`() {
        assertEquals(VoiceInputNext.RequestPermission, next(installed = true, granted = false, asked = false))
    }

    /** V4：没授权且问过 → 系统不再弹，只能送设置页（否则就是死循环弹框）。 */
    @Test
    fun `V4 未授权且问过则跳设置页`() {
        assertEquals(VoiceInputNext.GoSettings, next(installed = true, granted = false, asked = true))
    }

    /** V5：四条出路都必须可达——少一条说明判定退化了。 */
    @Test
    fun `V5 四条出路全部可达`() {
        val reachable = buildSet {
            for (installed in listOf(false, true)) {
                for (granted in listOf(false, true)) {
                    for (asked in listOf(false, true)) {
                        add(next(installed, granted, asked))
                    }
                }
            }
        }
        assertEquals(VoiceInputNext.values().toSet(), reachable)
    }
}
