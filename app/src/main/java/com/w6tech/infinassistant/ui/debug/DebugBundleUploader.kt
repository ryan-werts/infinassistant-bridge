package com.w6tech.infinassistant.ui.debug

/**
 * Sends a debug bundle to a network share. The SMB implementation lives in the `debug` source
 * set, a no-op in `release`; [createDebugBundleUploader] (one per source set) picks the one the
 * build has, so release builds ship no SMB client.
 */
interface DebugBundleUploader {

    /**
     * Upload [content] to the UNC share [uncPath] as [fileName], with [username] and [password]
     * (blank username = anonymous). Result.success holds the location written, Result.failure
     * says why not.
     */
    suspend fun upload(
        uncPath: String,
        fileName: String,
        content: ByteArray,
        username: String = "",
        password: String = "",
    ): Result<String>
}
