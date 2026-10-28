package com.nova.appmonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nova.appmonitor.R
import com.nova.appmonitor.model.FgAppInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
    private val items = ArrayList<FgAppInfo>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submit(list: List<FgAppInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.pkg.text = "${item.packageName}  ·  ${item.source}"
        holder.time.text = fmt.format(Date(item.timestampMs))
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tvItemLabel)
        val pkg: TextView = v.findViewById(R.id.tvItemPackage)
        val time: TextView = v.findViewById(R.id.tvItemTime)
    }
}
