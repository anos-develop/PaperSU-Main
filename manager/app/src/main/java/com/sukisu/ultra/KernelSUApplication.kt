package com.sukisu.ultra

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

lateinit var ksuApp: KernelSUApplication

class KernelSUApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.sukisu.ultra.ui.util.LocaleHelper.wrap(base))
    }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = SettingsRepositoryImpl().enablePredictiveBack
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        // paperSU: 只有 KernelSU 真的在跑时才预加载超级用户列表。
        //
        // 这一段以前是无条件执行的，而 SuperUserRepositoryImpl 走的是 libsu 的
        // RootService（IPC）。在没有 KernelSU 的机器上（内核 4.x + Magisk）它会去连
        // Magisk，libsu 拿不到 root service，就弹出
        // "需要下载完整版 Magisk 才能正常运行。开始下载?" —— 而且因为是在
        // Application.onCreate 里发的，弹窗是在后台弹的，用户在任何界面（连文件管理器里）
        // 都会看到，完全不知道是谁弹的。
        //
        // 而 Magisk 模式的超级用户页走的是 MagiskModeRoot 自己那套（直接读 magisk 的
        // policies 表），压根不需要这个列表。所以这里按"KernelSU 是否在运行"分流。
        val ksuRunning = runCatching { Natives.kernelUAPIVersion > 0 }.getOrDefault(false)
        if (ksuRunning) {
            val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
            superUserViewModel.loadAppList()
        } else {
            Log.i("paperSU", "KernelSU 未运行，跳过超级用户列表预加载（避免 libsu 弹下载 Magisk）")
        }

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "SukiSU/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
