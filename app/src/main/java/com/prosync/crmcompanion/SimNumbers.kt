package com.prosync.crmcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * This phone's own number per SIM, so the CRM knows which office line made or took
 * each call. Android only sometimes knows the number; an admin can type it in
 * Settings, and a typed number always wins.
 */
object SimNumbers {
    data class SimLine(val slot: Int, val subscriptionId: Int, val iccId: String?, val carrier: String, val detectedNumber: String)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission", "HardwareIds")
    fun lines(context: Context): List<SimLine> {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos: List<SubscriptionInfo> = runCatching { manager.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
        val canReadNumbers = granted(context, Manifest.permission.READ_PHONE_NUMBERS)
        return infos.map { info ->
            val detected = if (!canReadNumbers) "" else runCatching {
                if (Build.VERSION.SDK_INT >= 33) manager.getPhoneNumber(info.subscriptionId)
                else @Suppress("DEPRECATION") info.number.orEmpty()
            }.getOrDefault("")
            SimLine(
                slot = info.simSlotIndex + 1,
                subscriptionId = info.subscriptionId,
                iccId = runCatching { info.iccId }.getOrNull(),
                carrier = info.carrierName?.toString().orEmpty(),
                detectedNumber = detected.trim()
            )
        }.sortedBy { it.slot }
    }

    /** The number for a SIM slot: typed in Settings first, otherwise what Android reports. */
    fun numberForSlot(context: Context, slot: Int, line: SimLine? = null): String {
        val typed = SettingsStore(context).simNumber(slot)
        return typed.ifBlank { line?.detectedNumber.orEmpty() }
    }

    /**
     * The number of the SIM a call used. The call log's phone account id is the
     * subscription id on most phones and the SIM's ICCID on some others.
     */
    fun numberForCall(context: Context, phoneAccountId: String?): Pair<Int?, String> {
        val all = lines(context)
        val line = all.firstOrNull { phoneAccountId != null && (it.subscriptionId.toString() == phoneAccountId || it.iccId == phoneAccountId) }
            ?: all.singleOrNull()
        if (line != null) return line.slot to numberForSlot(context, line.slot, line)
        // No SIM info available: a single typed number is still the phone's number.
        val typed = SettingsStore(context).simNumber(1)
        return (if (typed.isBlank()) null else 1) to typed
    }
}
