package com.nx.vfremake.funClass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import com.nx.vfremake.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter


/**
 * 实验数据CSV文件信息（用于实验数据记录界面展示）
 */
data class ExperimentCsvFile(
    val documentUri: Uri,
    val displayName: String,
    val lastModified: Long,
    val sizeBytes: Long
)

/**
 * 实验数据保存目录状态
 */
enum class WriteDirStatus {
    NOT_SET,    // 从未选择过目录
    INVALID,    // 曾选择过，但持久化授权已丢失或目录已被删除/改名
    OK          // 目录可用
}

/**
 * 实验数据保存目录信息（供设置界面展示）
 * @param status:目录状态
 * @param displayPath:人类可读路径，如"内部存储/Download/exp"；未设置时为null
 */
data class WriteDirInfo(val status: WriteDirStatus, val displayPath: String?)

class DocuAndManageFun {
    /**
     *  选择文件夹
     * @note
     */
    fun selectDirectory(selectDirectoryLauncher: ActivityResultLauncher<Uri?>) {
        val downloadsUri =
            Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
        }
        selectDirectoryLauncher.launch(downloadsUri) // 也可以用null直接触发对象选择，忽略initial的downloadsUri
    }

    /**
     *  选择文件
     * @note
     */
    fun selectShapefile(selectFileLauncher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        selectFileLauncher.launch(intent)
    }

    /**
     * 检查实验数据保存目录是否可用，不可用时弹出目录选择器
     * @param  selectDirectoryLauncher:注册registerForActivityResult
     * @param  context:上下文
     * @return
     * @note   曾经只判断 SharedPreferences 里有没有存过 Uri 字符串：设备长期使用后持久化授权
     *         丢失（存储卷重挂载、目录被删/改名等）时字符串仍在，选择器永远不再弹出，保存
     *         协程每次都因无权限失败——现改为校验授权与目录存在性，失效即重新弹窗。
     *         设置界面也可随时直接重选目录（不依赖此处）。
     */
    fun cheekDirectoryUriPermission(
        selectDirectoryLauncher: ActivityResultLauncher<Uri?>,
        context: Context
    ) {
        val documentUri = getWriteDirDocumentUri(context)
        if (documentUri == null) {
            Log.d("SharedPre", "保存目录未设置或授权失效，弹出目录选择器")
            selectDirectory(selectDirectoryLauncher)
        } else {
            Log.d("SharedPre", "文件夹Uri: $documentUri")
        }
    }

    /**
     * 持久化用户新选择的实验数据保存目录（授权 + SharedPreferences）
     * @param  context:上下文
     * @param  treeUri:目录选择器返回的treeUri
     * @return 保存后的目录信息
     * @note   主界面首次作业弹窗与设置界面手动选择共用此入口
     */
    fun persistWriteDirectory(context: Context, treeUri: Uri): WriteDirInfo {
        val takeFlags: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        MySharedPreFun(context).persistDirectoryUriPermission(
            treeUri,
            takeFlags,
            context.getString(R.string.myWriteDir_DocumentUri_name)
        )
        return getWriteDirInfo(context)
    }

    /**
     * 获取实验数据保存目录的状态与可读路径（供设置界面展示）
     * @param  context:上下文
     * @return WriteDirInfo
     * @note
     */
    fun getWriteDirInfo(context: Context): WriteDirInfo {
        val uriString = MySharedPreFun(context).getMySharedPre()
            .getString(context.getString(R.string.myWriteDir_DocumentUri_name), null)
        if (uriString.isNullOrEmpty()) return WriteDirInfo(WriteDirStatus.NOT_SET, null)
        val displayPath = getWriteDirDisplayPath(Uri.parse(uriString))
        val status = if (getWriteDirDocumentUri(context) != null) WriteDirStatus.OK
                     else WriteDirStatus.INVALID
        return WriteDirInfo(status, displayPath)
    }

    /**
     * 将保存目录documentUri转换为人类可读路径
     * @param  documentUri:目录documentUri
     * @return 如"内部存储/Download/exp"、"SD卡(1234-5678)/exp"
     * @note   非外部存储provider时退化为documentId
     */
    private fun getWriteDirDisplayPath(documentUri: Uri): String {
        val docId = try {
            DocumentsContract.getDocumentId(documentUri)
        } catch (e: Exception) {
            return documentUri.toString()
        }
        if (documentUri.authority != "com.android.externalstorage.documents") return docId
        val split = docId.split(":", limit = 2)
        val volume = split[0]
        val path = split.getOrNull(1).orEmpty().trimEnd('/')
        val root = if (volume.equals("primary", ignoreCase = true)) "内部存储" else "SD卡($volume)"
        return if (path.isEmpty()) root else "$root/$path"
    }

    /**
     * 校验目录documentUri指向的目录当前是否仍存在
     * @param  context:上下文
     * @param  documentUri:目录documentUri
     * @return 是否存在
     * @note   目录被删除/改名时query抛异常或返回空游标
     */
    private fun documentExists(context: Context, documentUri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            Log.w("SharedPre", "保存目录不存在或不可访问: $documentUri", e)
            false
        }
    }

    /**
     * 检查app读写权限
     * @param  context:上下文
     * @return 是否有权限
     * @note
     */
    private fun checkSelfPermission(context: Context): Int {
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /**
     * 申请权限
     * @param  context:上下文
     * @return
     * @note
     */
    private fun requestPermissions(context: Context) {
        val writePermissionRequestCode = 101
        ActivityCompat.requestPermissions(
            context as Activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            writePermissionRequestCode // 定义一个请求码，用于识别权限请求
        )
    }

    /**
     * 将assets文件夹里的shp相关文件复制到外部专属存储目录下
     * @param  context:上下文
     * @return
     * @note   复制进了包名目录/files/shpFile下
     */
    fun copyShpFilesToExternalStorage(context: Context) {
        // 检查存储权限
        if (checkSelfPermission(context) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(context)
            return
        }

        // 获取外部存储目录
        val appSpecificExternalDir = context.getExternalFilesDir("shpFile") ?: return

        // 确保目录存在
        if (!appSpecificExternalDir.exists()) {
            appSpecificExternalDir.mkdirs()
        }

        // 获取AssetManager实例，用于访问应用的assets目录下的资源
        val assetManager = context.assets

        // 列出assets/shpFile目录下的所有文件
        val filesToCopy = assetManager.list("shpFile")?.toSet() ?: setOf()

        // 检查外部存储目录中的文件，如果不存在，则复制
        filesToCopy.forEach { fileName ->
            val sourceFile = "shpFile/$fileName"
            val destFile = File(appSpecificExternalDir, fileName)

            // 仅当目标文件不存在时，才执行复制操作
            if (!destFile.exists()) {
                val inputStream = assetManager.open(sourceFile)
                val outputStream = FileOutputStream(destFile)

                if (fileName.endsWith(".shp")) {
                    val shpPath = "$appSpecificExternalDir/$fileName"
                    Log.d("SharedPre", shpPath)
                    // 将初始shp的路径保存，并且首次运行置1表示已完成首次运行
                    MySharedPreFun(context).getMySharedPre().edit()
                        .putString(
                            context.getString(R.string.myLoadShpFile_AppSpecificExternalDirPath_name),
                            shpPath
                        ).putString(context.getString(R.string.runForTheFirstTime), "1").apply()
                }

                val buffer = ByteArray(1024)
                var bytesRead: Int

                // 循环读取输入流直到文件结尾
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // 将缓冲区中的数据写入到输出文件中
                    outputStream.write(buffer, 0, bytesRead)
                }

                // 关闭输入流和输出流，释放系统资源
                inputStream.close()
                outputStream.close()
            }
        }
    }

    /**
     * 获取实验数据保存目录的documentUri，并校验持久化授权仍有效、目录仍存在
     * @param  context:上下文
     * @return 目录documentUri；未设置、授权已被撤销或目录已不存在时返回null
     * @note
     */
    fun getWriteDirDocumentUri(context: Context): Uri? {
        val uriString = MySharedPreFun(context).getMySharedPre()
            .getString(context.getString(R.string.myWriteDir_DocumentUri_name), null)
        if (uriString.isNullOrEmpty()) return null
        // 存储的是 buildDocumentUriUsingTree 形式，前缀即用户授权的treeUri
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.isWritePermission && uriString.startsWith(it.uri.toString())
        }
        if (!stillGranted) return null
        val documentUri = Uri.parse(uriString)
        // 授权还在但目录本身被删/改名时 createDocument 同样会失败，一并视为失效
        return if (documentExists(context, documentUri)) documentUri else null
    }

    /**
     * 在实验数据保存目录下创建CSV文件并返回写入器
     * @param  context:上下文
     * @param  fileName:文件名（含.csv后缀）
     * @return OutputStreamWriter输出流写入器
     * @note   目录未设置/创建失败时抛IOException；须在IO线程调用
     */
    fun createExperimentCsvWriter(context: Context, fileName: String): OutputStreamWriter {
        // 校验授权与目录存在性，失效时给操作员明确指引（否则 createDocument 抛
        // SecurityException，提示里只有一串 content:// 看不懂）
        val documentUri = getWriteDirDocumentUri(context)
            ?: throw IOException("保存目录未设置或已失效，请在设置界面重新选择保存目录")

        Log.d("writeSaveData", documentUri.toString())

        // 使用SAF操作，因为使用的时间戳创建文件，不存在重复创建新文件的问题
        val fileUri = DocumentsContract.createDocument(
            context.contentResolver, documentUri, "text/csv", fileName
        ) ?: throw IOException("文件创建失败")
        // 获取documentUri对应的输出流
        val outputStream = context.contentResolver.openOutputStream(fileUri, "w")
            ?: throw IOException("写出数据输出流获取失败")
        return OutputStreamWriter(outputStream)
    }

    /**
     * 列举保存目录下的实验数据CSV文件（fertMsg_/depthTest_/depthRec_ 前缀），按修改时间倒序
     * @param  context:上下文
     * @return 文件信息列表；目录未设置/授权失效/查询异常时返回null
     * @note   须在IO线程调用
     */
    fun listExperimentCsvFiles(context: Context): List<ExperimentCsvFile>? {
        val dirUri = getWriteDirDocumentUri(context) ?: return null
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                dirUri, DocumentsContract.getDocumentId(dirUri)
            )
            val result = mutableListOf<ExperimentCsvFile>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val prefixes = listOf("fertMsg_", "depthTest_", "depthRec_")
                    if (!(name.endsWith(".csv") && prefixes.any { name.startsWith(it) })) continue
                    result.add(
                        ExperimentCsvFile(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId),
                            displayName = name,
                            lastModified = cursor.getLong(2),
                            sizeBytes = cursor.getLong(3)
                        )
                    )
                }
            } ?: return null
            result.sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            Log.e("ExperimentData", "列举实验数据文件失败", e)
            null
        }
    }

    /**
     * 删除一个实验数据CSV文件
     * @param  context:上下文
     * @param  documentUri:文件documentUri
     * @return 是否删除成功
     * @note   须在IO线程调用
     */
    fun deleteExperimentCsvFile(context: Context, documentUri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, documentUri)
        } catch (e: Exception) {
            Log.e("ExperimentData", "删除实验数据文件失败", e)
            false
        }
    }

    /**
     * 统计CSV文件的数据行数（总行数减去表头行）
     * @param  context:上下文
     * @param  documentUri:文件documentUri
     * @return 数据行数；读取失败返回-1
     * @note   须在IO线程调用，大文件会逐行读取
     */
    fun countCsvDataRows(context: Context, documentUri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(documentUri)?.bufferedReader()
                ?.useLines { lines -> (lines.count() - 1).coerceAtLeast(0) } ?: -1
        } catch (e: Exception) {
            Log.e("ExperimentData", "统计实验数据行数失败", e)
            -1
        }
    }

    /**
     * android 10获取绝对路径的一种方法
     * @param  context:上下文
     * @param  contentDocumentUri:contentDocumentUri
     * @return
     * @note   必须关闭分区存储才能使用，因为shp加载不能使用流或者Uri操作，只能这么暴力了
     */
    fun getPathFromContentUri(context: Context, contentDocumentUri: Uri): String? {
        // 检查传入的Uri是否是一个文档Uri
        if (DocumentsContract.isDocumentUri(context, contentDocumentUri)) {
            // 获取文档Uri的ID
            val docId = DocumentsContract.getDocumentId(contentDocumentUri)

            // 检查Uri的authority是否指向外部存储
            if ("com.android.externalstorage.documents" == contentDocumentUri.authority) {
                // 使用":"分割docId，获取存储类型和路径
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0] // 存储类型，例如"primary"
                val path = split[1] // 文件路径，例如"shp/VariableFert.shp"

                // 如果是主存储，则构造文件路径
                if ("primary".equals(type, ignoreCase = true)) {
                    val shpPath = Environment.getExternalStorageDirectory().toString() + "/" + path
                    Log.d("SharedPre", shpPath)
                    return shpPath
                }
            }
        }
        // 如果Uri不是文档Uri或者不是指向主存储，返回null
        return null
    }
}
