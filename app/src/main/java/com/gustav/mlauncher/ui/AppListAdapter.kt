package com.gustav.mlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gustav.mlauncher.R
import com.gustav.mlauncher.model.LaunchableApp

class AppListAdapter(
    private val onAppClicked: (LaunchableApp) -> Unit,
    private val onAppLongPressed: (LaunchableApp) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {
    private var items: List<LaunchableApp> = emptyList()
    private var highlightFirstItem: Boolean = false

    fun submitList(newItems: List<LaunchableApp>, highlightFirstItem: Boolean) {
        items = newItems
        this.highlightFirstItem = highlightFirstItem
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

        fun bind(app: LaunchableApp, showEnterMarker: Boolean) {
            labelView.text = app.label
            labelView.typeface = normalTypeface
            enterMarkerView.typeface = markerTypeface
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
