package com.dsh.mobile.v2.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据保险箱（v2）：origin + bearer token 落盘前经 AndroidKeyStore AES-GCM 加密。
 *
 * **多中继分槽（2026-10-27 按用户要求改）**：不同主机可能挂在不同中继上，手机要能
 * 同时保存多份「中继 → 令牌」。存储形态从「单份 origin/token」改为 JSON 数组：
 *
 * ```
 * [{ "origin": "https://relay.a.cc", "iv": "…", "token": "…" }, …]
 * ```
 *
 * 每份密文的 AAD 绑定自己的 origin，换槽解密互不干扰；`active` 记住当前选中哪条。
 * 单中继时代的老数据（origin/token/iv 三个平铺键）首次读取时自动迁移成数组的第一条。
 *
 * 与 v1 相同的容错口径：
 * - 密钥被新的锁屏凭据作废 / 密文与密钥不再匹配 → 清掉（以后每次读取都必然失败）；
 * - Keystore 瞬时故障（锁屏变更中、keymaster 忙等）→ 保留密文、这次当作未登录跳过，
 *   清空会把一次可恢复的故障变成强制重新登录。
 */
class TokenVault(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as SecretKey
    }

    // ------------------------------------------------------------------
    // 多中继：槽位读写
    // ------------------------------------------------------------------

    /** 已保存的全部中继源（迁移后含老的单份）。 */
    fun origins(): List<String> = readSlots().map { it.origin }

    /** 当前选中的中继源；无任何槽位时为空串。 */
    fun origin(): String {
        migrateLegacyIfNeeded()
        val slots = readSlots()
        val active = prefs.getString("active", "") ?: ""
        return slots.firstOrNull { it.origin == active }?.origin
            ?: slots.firstOrNull()?.origin
            ?: ""
    }

    /** 切换当前中继；origin 不在已存槽位里则不动。 */
    fun select(origin: String): Boolean {
        val slots = readSlots()
        if (slots.none { it.origin == origin }) return false
        prefs.edit().putString("active", origin).apply()
        return true
    }

    /** 保存（新增或覆盖）一份中继令牌，并把它设为当前选中。 */
    fun save(origin: String, token: String) {
        migrateLegacyIfNeeded()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val slot = JSONObject()
            .put("origin", origin)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("token", Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val slots = readSlots().filter { it.origin != origin }.map { it.raw }.toMutableList()
        slots.add(slot)
        prefs.edit()
            .putString(SLOTS_KEY, JSONArray(slots).toString())
            .putString("active", origin)
            .apply()
    }

    /** 当前选中中继的令牌；未登录/解密失败为空串。 */
    fun load(): String {
        migrateLegacyIfNeeded()
        val active = origin()
        if (active.isEmpty()) return ""
        return loadFor(active)
    }

    /** 指定中继的令牌（扫码登录时用：还没切换 active 就要先验码）。 */
    fun loadFor(origin: String): String {
        return try {
            val slot = readSlots().firstOrNull { it.origin == origin } ?: return ""
            val data = slot.raw.optString("token", "")
            if (data.isEmpty()) return ""
            if (data.startsWith("raw:")) return data.substring(4)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(slot.raw.optString("iv", ""), Base64.NO_WRAP)))
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            clear()
            ""
        } catch (e: javax.crypto.AEADBadTagException) {
            removeSlot(origin)
            ""
        } catch (e: Exception) {
            // Keystore 瞬时故障：保留密文，这次当作未登录跳过。
            ""
        }
    }

    /** 删除一份中继槽位；删的是当前选中则 active 落到剩余第一条。 */
    fun removeSlot(origin: String) {
        val remaining = readSlots().filter { it.origin != origin }
        val next = if (origin() == origin) remaining.firstOrNull()?.origin ?: "" else origin()
        prefs.edit()
            .putString(SLOTS_KEY, JSONArray(remaining.map { it.raw }).toString())
            .putString("active", next)
            .apply()
    }

    /** 全部清空（退出登录用）。 */
    fun clear() {
        prefs.edit()
            .remove(SLOTS_KEY).remove("active")
            .remove("token").remove("iv").remove("origin")
            .apply()
    }

    // ------------------------------------------------------------------
    // 偏好（与凭据无关，沿用单份键）
    // ------------------------------------------------------------------

    /**
     * Content classes the phone declines; sent as x-dsh-omit so the host never uploads them.
     * 思考（reasoning）与工具载荷（tool）必须下发：手机的「模型处理详情」要展开它们。
     */
    fun omit(): String {
        var value = prefs.getString("omit", DEFAULT_OMIT) ?: DEFAULT_OMIT
        // 旧版本默认丢弃 reasoning/tool；沿用旧值会让处理详情面板永远为空。
        if (LEGACY_OMIT == value) {
            value = DEFAULT_OMIT
            omit(value)
        }
        return value
    }

    fun omit(value: String) { prefs.edit().putString("omit", value).apply() }

    /** 偏好：断线自动重连（默认开）。 */
    fun autoReconnect(): Boolean = prefs.getBoolean("autoReconnect", true)
    fun autoReconnect(value: Boolean) { prefs.edit().putBoolean("autoReconnect", value).apply() }

    /** 偏好：编辑内容（默认关）。 */
    fun editContent(): Boolean = prefs.getBoolean("editContent", false)
    fun editContent(value: Boolean) { prefs.edit().putBoolean("editContent", value).apply() }

    /**
     * 偏好：中继为本设备传输附件（默认开）。
     *
     * **只是缓存**：真值在服务端 `devices.attachments`，每次进设置页/登录后
     * 由 `RelayGateway.loadAttachments` 拉回来覆盖。存本地是为了冷启动那一帧
     * 不要闪一下「开着」再跳成「关着」。
     */
    fun transferAttachments(): Boolean = prefs.getBoolean("transferAttachments", true)
    fun transferAttachments(value: Boolean) {
        prefs.edit().putBoolean("transferAttachments", value).apply()
    }

    // ------------------------------------------------------------------
    // 内部：槽位序列化与迁移
    // ------------------------------------------------------------------

    private class Slot(val origin: String, val raw: JSONObject)

    private fun readSlots(): MutableList<Slot> {
        val text = prefs.getString(SLOTS_KEY, "") ?: ""
        if (text.isEmpty()) return mutableListOf()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val origin = obj.optString("origin", "")
                if (origin.isEmpty()) null else Slot(origin, obj)
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 单中继时代的平铺键一次性搬进数组；只看得到就搬，看不到不写任何东西。 */
    private fun migrateLegacyIfNeeded() {
        if (prefs.contains(SLOTS_KEY)) return
        val legacyOrigin = prefs.getString("origin", "") ?: ""
        val legacyToken = prefs.getString("token", "") ?: ""
        if (legacyOrigin.isEmpty() || legacyToken.isEmpty()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(legacyOrigin.toByteArray(Charsets.UTF_8))
        val slot = JSONObject()
            .put("origin", legacyOrigin)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("token", Base64.encodeToString(cipher.doFinal(legacyToken.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        prefs.edit()
            .putString(SLOTS_KEY, JSONArray().put(slot).toString())
            .putString("active", legacyOrigin)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dsh-v2-session"
        private const val KEY_ALIAS = "dsh-v2-session-key"
        private const val SLOTS_KEY = "slots"

        /** 默认只丢宿主上下文记录（request/header、system/message 等）。 */
        const val DEFAULT_OMIT = "context"

        /** 早期版本的默认值，升级时一次性迁移掉。 */
        private const val LEGACY_OMIT = "reasoning,tool,context"
    }
}
