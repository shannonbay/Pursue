package app.getpursue.utils

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide

object IconUrlUtils {
    private const val RES_PREFIX = "res://drawable/"

    fun resolveDrawableResId(context: Context, iconUrl: String): Int {
        if (!iconUrl.startsWith(RES_PREFIX)) return 0
        val name = iconUrl.removePrefix(RES_PREFIX)
        return context.resources.getIdentifier(name, "drawable", context.packageName)
    }

    fun loadInto(context: Context, iconUrl: String?, imageView: ImageView): Boolean {
        if (iconUrl == null) return false
        if (iconUrl.startsWith(RES_PREFIX)) {
            val resId = resolveDrawableResId(context, iconUrl)
            if (resId != 0) {
                imageView.setImageResource(resId)
                return true
            }
            return false
        }
        if (iconUrl.startsWith("https://")) {
            Glide.with(context).load(iconUrl).centerCrop().into(imageView)
            return true
        }
        return false
    }
}
