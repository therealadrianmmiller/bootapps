package com.adrianmmiller.bamina

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BootAppsAdapter(
    private val items: MutableList<BootAppInfo>,
    private val onSelectionChanged: (BootAppInfo, Boolean) -> Unit,
    private val onRowClicked: (BootAppInfo) -> Unit
) : RecyclerView.Adapter<BootAppsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox_selected)
        val label: TextView = view.findViewById(R.id.text_app_label)
        val packageName: TextView = view.findViewById(R.id.text_package_name)
        val statusBadge: TextView = view.findViewById(R.id.text_status_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_boot_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.label.text = item.appLabel
        holder.packageName.text = item.packageName
        holder.statusBadge.text = if (item.receiversEnabled) "Boot: on" else "Boot: off"

        if (item.receiversEnabled) {
            holder.itemView.setBackgroundColor(Color.parseColor("#E8F5E9")) // soft green — boot on
            holder.statusBadge.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFEBEE")) // soft red — boot off
            holder.statusBadge.setTextColor(Color.parseColor("#C62828"))
        }

        // Avoid firing the listener while recycling views.
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.isSelected
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onSelectionChanged(item, isChecked)
        }

        holder.itemView.setOnClickListener {
            holder.checkbox.isChecked = !holder.checkbox.isChecked
        }
        holder.itemView.setOnLongClickListener {
            onRowClicked(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<BootAppInfo>) {
        val snapshot = ArrayList(newItems) // defensive copy: newItems may be the same
        items.clear()                      // list object as `items`, so clearing first
        items.addAll(snapshot)             // would otherwise wipe newItems too
        notifyDataSetChanged()
    }

    fun applyImportedSelection(packageNames: Set<String>) {
        items.forEach { it.isSelected = packageNames.contains(it.packageName) }
        notifyDataSetChanged()
    }

    fun selectAll(select: Boolean) {
        items.forEach { it.isSelected = select }
        notifyDataSetChanged()
    }
}
