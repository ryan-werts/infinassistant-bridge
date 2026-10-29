package com.w6tech.infinassistant.ui.debug

/** The release build's uploader: one that does nothing, so no SMB client is shipped. */
fun createDebugBundleUploader(): DebugBundleUploader = NoOpDebugBundleUploader()
