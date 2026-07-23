package com.emotionai.nfc.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.nio.charset.StandardCharsets

object NfcHandler {

    /**
     * 从 NFC Intent 中提取 chipId
     * 支持所有 NFC intent 类型 (NDEF/TECH/TAG_DISCOVERED)
     *
     * 读取策略:
     *   1. 优先从 EXTRA_NDEF_MESSAGES 中解析 (NDEF_DISCOVERED 标准路径)
     *   2. 若失败, 从 Tag 直读 cachedNdefMessage (TECH_DISCOVERED 兼容)
     */
    fun extractChipId(intent: Intent): String? {
        // 策略1: 从 NDEF 消息中提取
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (raw != null) {
            val chipPrefix = "emoai://chip/"
            for (msg in raw) {
                if (msg is NdefMessage) {
                    for (record in msg.records) {
                        val payload = record.payload
                        if (payload != null) {
                            val uri = extractUriFromNdefPayload(payload) ?: continue
                            val index = uri.indexOf(chipPrefix)
                            if (index >= 0) {
                                return uri.substring(index + chipPrefix.length)
                            }
                        }
                    }
                }
            }
        }

        // 策略2: 从 Tag 直读 (兼容某些设备 TECH_DISCOVERED 不带 NDEF Extra 的情况)
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return null
        return readNdefFromTag(tag)
    }

    private fun readNdefFromTag(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val msg = ndef.cachedNdefMessage ?: run { ndef.close(); return null }
            ndef.close()
            extractFromNdefMessage(msg)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromNdefMessage(msg: NdefMessage): String? {
        val chipPrefix = "emoai://chip/"
        for (record in msg.records) {
            val payload = record.payload
            if (payload != null) {
                val uri = extractUriFromNdefPayload(payload) ?: continue
                val index = uri.indexOf(chipPrefix)
                if (index >= 0) {
                    return uri.substring(index + chipPrefix.length)
                }
            }
        }
        return null
    }

    /**
     * 从 NDEF Record Payload 中提取完整 URI
     * NDEF URI 格式: [IdentifierCode(1B)][URI字符串]
     */
    private fun extractUriFromNdefPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null

        val uriPrefixes = arrayOf(
            "",              // 0x00
            "http://www.",   // 0x01
            "https://www.",  // 0x02
            "http://",       // 0x03
            "https://",      // 0x04
            "tel:",          // 0x05
            "mailto:"        // 0x06
        )

        val identifierCode = payload[0].toInt()
        val uriString = String(payload.copyOfRange(1, payload.size), StandardCharsets.UTF_8)

        return if (identifierCode < uriPrefixes.size) {
            uriPrefixes[identifierCode] + uriString
        } else {
            uriString
        }
    }

    /**
     * 判断 Intent 是否为 NFC 发现事件
     */
    fun isNfcIntent(intent: Intent): Boolean {
        return intent.action in listOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED
        )
    }
}
