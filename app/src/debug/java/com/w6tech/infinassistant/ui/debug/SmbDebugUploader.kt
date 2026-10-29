package com.w6tech.infinassistant.ui.debug

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

/**
 * Uploads a byte array to a UNC SMB share anonymously.
 *
 * Supports paths of the form \\host\share or \\host\share\sub\dir.
 * Forward slashes are tolerated in addition to backslashes.
 * Directories are created as needed.
 */
class SmbDebugUploader : DebugBundleUploader {

    /**
     * Upload [content] to [uncPath] as [fileName].
     *
     * @param uncPath  UNC share path, e.g. \\host\share or
     *                 \\host\share\debug
     * @param fileName Target filename on the share.
     * @param content  Bytes to write.
     * @return Result.success with the smb:// URI string on success, or
     *         Result.failure with a descriptive exception on any error.
     */
    override suspend fun upload(
        uncPath: String,
        fileName: String,
        content: ByteArray,
        username: String,
        password: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(uncPath.isNotBlank()) { "SMB upload not configured" }
            val (host, shareName, subDir) = parseUnc(uncPath)
            // Blank username → anonymous; otherwise NTLM with the supplied creds
            // (domain null = local/workgroup account).
            val auth = if (username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username.trim(), password.toCharArray(), null)
            }

            SMBClient().use { client ->
                client.connect(host).use { connection ->
                    val session = connection.authenticate(auth)
                    (session.connectShare(shareName) as DiskShare).use { share ->
                        // Create subdirectory hierarchy if needed
                        if (subDir.isNotEmpty()) {
                            var accumulated = ""
                            for (segment in subDir.split("/").filter { it.isNotBlank() }) {
                                accumulated = if (accumulated.isEmpty()) segment else "$accumulated\\$segment"
                                if (!share.folderExists(accumulated)) {
                                    share.mkdir(accumulated)
                                }
                            }
                        }

                        val filePath = if (subDir.isEmpty()) fileName else "${subDir.replace('/', '\\')}\\$fileName"

                        share.openFile(
                            filePath,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null,
                        ).use { file ->
                            file.outputStream.use { out -> out.write(content) }
                        }

                        "smb://$host/$shareName/${if (subDir.isEmpty()) "" else "$subDir/"}$fileName"
                    }
                }
            }
        }
    }

    /**
     * Parse a UNC path into (host, shareName, subDir).
     * subDir is '/'-joined remaining segments (may be empty string).
     */
    private fun parseUnc(uncPath: String): Triple<String, String, String> {
        // Normalise separators and strip leading slashes
        val normalised = uncPath.replace('\\', '/').trimStart('/')
        val segments = normalised.split('/').filter { it.isNotBlank() }
        require(segments.size >= 2) {
            "UNC path must contain at least \\\\host\\share, got: $uncPath"
        }
        val host = segments[0]
        val shareName = segments[1]
        val subDir = segments.drop(2).joinToString("/")
        return Triple(host, shareName, subDir)
    }
}
