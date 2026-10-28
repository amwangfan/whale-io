package com.nova.appmonitor.model

data class FgAppInfo(
    val packageName: String,
    val label: String,
    val timestampMs: Long,
    val source: String
)
