package com.v2ray.ang.handler

import androidx.work.Constraints
import androidx.work.NetworkType

/** Bulk periodic downloads must not compete with an interactive session or use metered data. */
internal val backgroundTransferConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresCharging(true)
    .setRequiresDeviceIdle(true)
    .build()
