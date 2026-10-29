package com.w6tech.infinassistant.ui.debug

/**
 * Release stand-in for the debug-only SMB uploader.
 *
 * The SMB implementation lives in `src/debug/` and pulls in smbj, slf4j and Bouncy Castle, which
 * are `debugImplementation`: a full SMB client and a multi-MB crypto provider have no place in a
 * release build for a feature that only collects debug bundles. The shared UI talks to
 * [DebugBundleUploader], so it compiles unchanged, and this class keeps the SMB class names out
 * of the release dex.
 */
class NoOpDebugBundleUploader : DebugBundleUploader {

    override suspend fun upload(
        uncPath: String,
        fileName: String,
        content: ByteArray,
        username: String,
        password: String,
    ): Result<String> = Result.failure(
        UnsupportedOperationException("SMB debug upload is available in debug builds only")
    )
}
