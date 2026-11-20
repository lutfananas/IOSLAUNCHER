/*
 * iOS Launcher — Copyright (c) 2026 lutfananas. All rights reserved. See LICENSE.
 */
package com.ioslauncher.model

import android.content.ComponentName
import android.os.UserHandle

data class AppInfo(
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val category: String,
    val installTime: Long,
    val isWorkProfile: Boolean,
) {
    val key: String = "${component.flattenToString()}#$userSerial"
    val packageName: String get() = component.packageName
}
