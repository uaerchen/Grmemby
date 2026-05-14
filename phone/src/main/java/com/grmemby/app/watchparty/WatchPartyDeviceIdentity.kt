package com.grmemby.app.watchparty

import android.content.Context
import java.util.UUID

object WatchPartyDeviceIdentity {
    private const val PreferencesName = "grmemby_watch_party_device"
    private const val DeviceMemberIdKey = "device_member_id"
    private const val MemberIdPrefix = "device-"

    fun memberId(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val existing = preferences.getString(DeviceMemberIdKey, null)?.takeIf { it.isValidMemberId() }
        if (existing != null) return existing

        val created = MemberIdPrefix + UUID.randomUUID().toString().replace("-", "")
        preferences.edit().putString(DeviceMemberIdKey, created).apply()
        return created
    }

    private fun String.isValidMemberId(): Boolean {
        return startsWith(MemberIdPrefix) && length in 16..80 && all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
