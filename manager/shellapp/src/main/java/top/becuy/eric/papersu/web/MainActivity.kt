// ---------------------------------------------------------------------------
// paperSU 网页壳
//   打开后显示 3 秒，然后向 paperSU 的授权接口要那个带密钥的链接，拿到就跳过去。
//   它自己不做任何 root 操作，也没有任何提权代码：链接是管理器给的，
//   管理器只发给白名单里的包名，所以没被授权时这里会明说原因。
// ---------------------------------------------------------------------------
package top.becuy.eric.papersu.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        /** 纸SU 管理器里的授权接口（authority = <管理器包名>.parasite.auth） */
        private const val AUTH_URI = "content://top.becuy.eric.papersu.parasite.auth"
        private const val METHOD_GET_URL = "getWebAdminUrl"
        private const val WAIT_MS = 3000L
    }

    private lateinit var status: TextView
    private lateinit var manual: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
        ).toInt()

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            text = getString(R.string.entering)
        }
        manual = Button(this).apply {
            setText(R.string.open_manual)
            visibility = Button.GONE
            setOnClickListener { open(authUrl() ?: "") }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(pad, pad, pad, pad)
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(manual, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({ go() }, WAIT_MS)
    }

    /** 向管理器要链接；不在白名单里 / 网页端没开，都会带着原因返回。 */
    private fun authUrl(): String? {
        val res = runCatching {
            contentResolver.call(Uri.parse(AUTH_URI), METHOD_GET_URL, null, null)
        }.getOrNull()
        val url = res?.getString("result")
        return if (!url.isNullOrBlank()) url else null
    }

    private fun authError(): String? {
        val res = runCatching {
            contentResolver.call(Uri.parse(AUTH_URI), METHOD_GET_URL, null, null)
        }.getOrNull()
        return res?.getString("error")
    }

    private fun go() {
        val url = authUrl()
        if (!url.isNullOrBlank()) {
            open(url)
            return
        }
        val err = authError()
        manual.visibility = Button.VISIBLE
        status.text = when {
            err == null ->
                getString(R.string.webadmin_off)
            else -> getString(R.string.not_authorized)
        }
    }

    private fun open(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, R.string.not_authorized, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            finish()
        }.onFailure {
            if (it is ActivityNotFoundException) {
                Toast.makeText(this, R.string.not_authorized, Toast.LENGTH_LONG).show()
            }
        }
    }
}