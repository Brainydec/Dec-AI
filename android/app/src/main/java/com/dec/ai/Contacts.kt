package com.dec.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    private fun has(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Returns (displayName, number) for a spoken name or a literal phone number. */
    fun find(ctx: Context, target: String): Pair<String, String>? {
        val t = target.trim()
        if (t.length >= 5 && t.none { it.isLetter() }) return t to t.filter { it.isDigit() || it == '+' }
        if (!has(ctx)) return null
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            p, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$t%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) to c.getString(1) }
        return null
    }

    fun nameFor(ctx: Context, number: String): String? {
        if (!has(ctx)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
