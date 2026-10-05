package com.zhusijiao.app.ui.common

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.zhusijiao.app.R
import com.zhusijiao.app.util.Ui

/**
 * 「支持开发」底部面板：一段说明加微信赞赏码。
 *
 * 只是一个入口，不做任何引导付费的设计：不弹窗提醒、不记录是否打赏、主按钮是「完成」。
 * 微信不允许别的应用直接调起赞赏，所以提供「保存到相册」，用户自己到微信扫一扫里从相册选。
 */
class SupportSheet(context: Context) : Dialog(context, R.style.Theme_Zhusijiao_Sheet) {

    init {
        setContentView(R.layout.dialog_support)
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        setCanceledOnTouchOutside(true)

        // 赞赏码放在 res/raw：保存到相册时要原样写出文件，不能经过解码再压缩
        val qr = runCatching {
            context.resources.openRawResource(R.raw.reward_wechat).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        findViewById<ImageView>(R.id.supportQr).setImageBitmap(qr)

        val save = findViewById<TextView>(R.id.supportSave)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            save.setOnClickListener { saveToGallery() }
        } else {
            // Android 9 及以下写相册要申请存储权限，为一张图不值得多要一项权限：改为提示截图
            save.visibility = View.GONE
            findViewById<TextView>(R.id.supportHint).setText(R.string.support_hint_screenshot)
        }
        findViewById<TextView>(R.id.supportClose).setOnClickListener { dismiss() }
    }

    /** 把赞赏码原图写进系统相册的「Pictures/几点上课」，Android 10+ 不需要任何权限。 */
    private fun saveToGallery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + context.getString(R.string.app_name)
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            Ui.toastError(context, context.getString(R.string.support_save_failed))
            return
        }
        try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output)
                context.resources.openRawResource(R.raw.reward_wechat).use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Ui.toastSuccess(context, context.getString(R.string.support_saved), openWeChatAction())
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Ui.toastError(context, context.getString(R.string.support_save_failed))
        }
    }

    /** 装了微信才给「打开微信」；没装就只提示已保存。 */
    private fun openWeChatAction(): AppToast.Action? {
        val launch = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE) ?: return null
        return AppToast.Action(context.getString(R.string.support_open_wechat)) {
            runCatching { context.startActivity(launch) }
        }
    }

    private companion object {
        const val FILE_NAME = "jidianshangke-reward-wechat.jpg"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
