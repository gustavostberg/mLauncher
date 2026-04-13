package com.gustav.mlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.TypedValue
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import com.gustav.mlauncher.R
import com.gustav.mlauncher.model.LaunchableApp

class AppListAdapter(
    private val onAppClicked: (LaunchableApp) -> Unit,
    private val onAppLongPressed: (LaunchableApp) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {
    private var items: List<LaunchableApp> = emptyList()
    private var highlightFirstItem: Boolean = false
    private var labelTextSizeDp: Float = 24f
    private var labelTextColor: Int = Color.BLACK

    fun submitList(newItems: List<LaunchableApp>, highlightFirstItem: Boolean) {
        items = newItems
        this.highlightFirstItem = highlightFirstItem
        notifyDataSetChanged()
    }

    fun setLabelTextSizeDp(sizeDp: Float) {
        if (labelTextSizeDp == sizeDp) {
            return
        }
        labelTextSizeDp = sizeDp
        notifyDataSetChanged()
    }

    fun setLabelTextColor(color: Int) {
        if (labelTextColor == color) {
            return
        }
        labelTextColor = color
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view, onAppClicked, onAppLongPressed)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(
            app = items[position],
            showEnterMarker = highlightFirstItem && position == 0,
            labelTextSizeDp = labelTextSizeDp,
            labelTextColor = labelTextColor,
        )
    }

    override fun getItemCount(): Int = items.size

    class AppViewHolder(
        itemView: View,
        private val onAppClicked: (LaunchableApp) -> Unit,
        private val onAppLongPressed: (LaunchableApp) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val normalTypeface = AppFonts.regular(itemView.context)
        private val markerTypeface = AppFonts.medium(itemView.context)
        private val labelView: TextView = itemView.findViewById(R.id.appLabel)
        private val enterMarkerView: TextView = itemView.findViewById(R.id.appEnterMarker)

        fun bind(
            app: LaunchableApp,
            showEnterMarker: Boolean,
            labelTextSizeDp: Float,
            labelTextColor: Int,
        ) {
            labelView.text = app.label
            labelView.typeface = normalTypeface
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, labelTextSizeDp)
            labelView.setTextColor(labelTextColor)
            enterMarkerView.typeface = markerTypeface
            enterMarkerView.setTextColor(labelTextColor)
            enterMarkerView.visibility = if (showEnterMarker) View.VISIBLE else View.INVISIBLE

            itemView.setOnClickListener {
                onAppClicked(app)
            }
            itemView.setOnLongClickListener {
                onAppLongPressed(app)
                true
            }
        }
    }
}
