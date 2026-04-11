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


class DocuAndManageFun {
    /**
     *  选择文件夹
     * @note
     */
    private fun selectDirectory(selectDirectoryLauncher: ActivityResultLauncher<Uri?>) {
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
     * 检查sharedpre是否有文件夹的Uri
     * @param  resId:资源值ID
     * @param  selectDirectoryLauncher:注册registerForActivityResult
     * @param  context:上下文
     * @return
     * @note
     */
    fun cheekDirectoryUriPermission(
        resId: Int,
        selectDirectoryLauncher: ActivityResultLauncher<Uri?>,
        context: Context
    ) {
        val resKey = context.getString(resId)
        val sharedPre = MySharedPreFun(context).getMySharedPre()
        if (!sharedPre.contains(resKey) ||
            sharedPre.getString(resKey, null).isNullOrEmpty()
        ) {
            selectDirectory(selectDirectoryLauncher)
        }

        val documentUri = sharedPre.getString(resKey, null)
        Log.d(
            "SharedPre", "文件夹Uri: " + documentUri.toString()

        )
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
