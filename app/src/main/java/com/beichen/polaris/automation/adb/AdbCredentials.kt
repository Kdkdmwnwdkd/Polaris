package com.beichen.polaris.automation.adb

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.beichen.polaris.core.AgentLogger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * W-51：无线调试通道的**凭据与端点**持久化。
 *
 * 存什么、为什么存：
 * - **RSA 密钥对**：adb 的身份凭据。配对时把公钥交给设备，之后每次连接用私钥签 20 字节
 *   challenge。丢了就要重新配对，泄漏等于对方获得 shell 权限——所以只落 SharedPreferences
 *   （`MODE_PRIVATE`，应用私有目录），不写外部存储、不进备份、不进日志。
 * - **host / 连接端口**：无线调试的连接端口是随机分配且会变的，记住它可省一次手输；
 *   连不上时以「明确报错 + 重新填」收场，不静默重试到超时。
 * - 配对端口（pairing port）**不持久化**：它是一次性的，配对完成即失效。
 *
 * 线程纪律：RSA 生成 ~100ms 级，[keyPair] 首次调用会做一次生成，调用方应在 IO 线程
 * 调用（[AdbSession] 内部已保证）。
 */
object AdbCredentials {

    private const val TAG = "AdbCredentials"
    private const val PREF = "agentkit_adb_channel"
    private const val K_PRIV = "priv_pkcs8"
    private const val K_PUB = "pub_x509"
    private const val K_HOST = "host"
    private const val K_CONNECT_PORT = "connect_port"

    private var prefs: SharedPreferences? = null
    private var cached: KeyPair? = null

    fun init(context: Context) {
        prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    /** 是否已配对过（有密钥 + 端点）。 */
    fun hasEndpoint(): Boolean =
        !host().isNullOrBlank() && connectPort() > 0

    /**
     * 丢掉 SharedPreferences 句柄与内存里的密钥对（仅供
     * [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * **不删任何落盘数据**：`init(context)` 之后读到的仍是同一份配对信息。
     * 之所以要清 `cached`，是因为 harness 会在不同的临时 Context 之间切换，
     * 留着上一轮的解密结果等于让用例互相污染。
     */
    fun reset() {
        prefs = null
        cached = null
    }

    fun host(): String? = prefs?.getString(K_HOST, null)

    fun connectPort(): Int = prefs?.getInt(K_CONNECT_PORT, 0) ?: 0

    fun saveEndpoint(host: String, connectPort: Int) {
        prefs?.edit()?.putString(K_HOST, host)?.putInt(K_CONNECT_PORT, connectPort)?.apply()
        AgentLogger.i(TAG, "无线调试端点已保存：$host:$connectPort")
    }

    /**
     * 本机 adb 密钥对（首次调用生成并持久化）。
     *
     * @return null 表示存储不可用（[init] 未调用或 SP 打不开）——调用方如实降级，
     *   不抛异常到 UI 层。
     */
    fun keyPair(): KeyPair? {
        cached?.let { return it }
        val p = prefs ?: return null

        val privB64 = p.getString(K_PRIV, null)
        val pubB64 = p.getString(K_PUB, null)
        if (privB64 != null && pubB64 != null) {
            val loaded = runCatching {
                val kf = KeyFactory.getInstance("RSA")
                KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                )
            }.onFailure { AgentLogger.w(TAG, "已存密钥解析失败，将重新生成：${it.message}") }.getOrNull()
            if (loaded != null) {
                cached = loaded
                return loaded
            }
        }

        val fresh = AdbCrypto.generateKeyPair()
        val ok = runCatching {
            p.edit()
                .putString(K_PRIV, Base64.encodeToString(fresh.private.encoded, Base64.NO_WRAP))
                .putString(K_PUB, Base64.encodeToString(fresh.public.encoded, Base64.NO_WRAP))
                .commit()   // 密钥必须写成功才能继续配对，用 commit 同步落盘
        }.getOrDefault(false)
        if (!ok) {
            AgentLogger.w(TAG, "密钥持久化失败：本次会话可用，重启后需重新配对")
        }
        cached = fresh
        AgentLogger.i(TAG, "已生成新的 adb 密钥对（2048-bit RSA，公钥指纹见配对日志）")
        return fresh
    }

    /** 清除端点（保留密钥，便于重新配对同一台设备的另一个端口）。 */
    fun clearEndpoint() {
        prefs?.edit()?.remove(K_HOST)?.remove(K_CONNECT_PORT)?.apply()
    }

    /** 彻底重置（换设备 / 怀疑凭据泄漏时用）。 */
    fun resetAll() {
        cached = null
        prefs?.edit()?.clear()?.apply()
        AgentLogger.i(TAG, "无线调试凭据已重置")
    }
}
