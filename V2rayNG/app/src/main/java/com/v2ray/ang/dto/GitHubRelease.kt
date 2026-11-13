package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    @SerializedName("body")
    val body: String,
    @SerializedName("assets")
    val assets: List<Asset>,
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
    @SerializedName("published_at")
    val publishedAt: String = ""
) {
    data class Asset(
        @SerializedName("name")
        val name: String,
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String,
        /** Size in bytes. */
        @SerializedName("size")
        val size: Long = 0L,
        /** Checksum as GitHub publishes it, e.g. `sha256:<hex>`; absent on old releases. */
        @SerializedName("digest")
        val digest: String? = null
    )
}