package de.dreier.mytargets.utils

import android.view.View
import android.widget.FrameLayout
import androidx.databinding.BindingAdapter

/**
 * DataBinding adapters for custom attributes
 */
object DataBindingAdapters {

    @JvmStatic
    @BindingAdapter("propertyShouldShow", "propertyShowAll", "propertyValue", requireAll = false)
    fun setPropertyVisibility(
        view: FrameLayout,
        shouldShow: Boolean?,
        showAll: Boolean?,
        propertyValue: Any?
    ) {
        val hasValue = when (propertyValue) {
            null -> false
            is String -> propertyValue.replace("null", "").isNotBlank()
            else -> propertyValue.toString().replace("null", "").isNotBlank()
        }
        val visible = shouldShow == true && (showAll == true || hasValue)
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
