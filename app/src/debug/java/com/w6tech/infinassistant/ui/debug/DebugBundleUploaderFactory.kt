package com.w6tech.infinassistant.ui.debug

/** The debug build's uploader: SMB. */
fun createDebugBundleUploader(): DebugBundleUploader = SmbDebugUploader()
