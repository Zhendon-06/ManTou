package com.hfad.mantou.utils.project

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap

internal class WorkspaceResourceDocuments(
    private val resolver: ContentResolver,
    private val tree: Uri
) {
    fun readFolder(): WorkspaceResourceEntry.Folder {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val name = resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: error("无法读取所选文件夹")
        return folder(id, name)
    }

    private fun folder(id: String, name: String): WorkspaceResourceEntry.Folder =
        WorkspaceResourceEntry.Folder(name) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0)
                        val childName = cursor.getString(1)
                        val mimeType = cursor.getString(2)
                        if (mimeType == Document.MIME_TYPE_DIR) {
                            add(folder(childId, childName))
                        } else {
                            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                            val fileName = if ('.' !in childName && extension != null) "$childName.$extension" else childName
                            val file = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                            add(WorkspaceResourceEntry.Resource(fileName) {
                                resolver.openInputStream(file) ?: error("无法读取「$childName」")
                            })
                        }
                    }
                }
            } ?: error("无法读取文件夹「$name」")
        }
}
