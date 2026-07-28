package com.example.cachecleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val items: MutableList<AppCacheInfo>,
    private val onClick: (AppCacheInfo, Int) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.packageName)
        val size: TextView = view.findViewById(R.id.cacheSize)
        val check: ImageView = view.findViewById(R.id.visitedCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.appName
        holder.pkg.text = item.packageName
        holder.size.text = item.formattedSize()
        holder.icon.setImageDrawable(item.icon)
        holder.check.visibility = if (item.visited) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(item, position) }
    }

    override fun getItemCount() = items.size

    fun markVisited(position: Int) {
        items[position].visited = true
        notifyItemChanged(position)
    }

    fun updateList(newItems: List<AppCacheInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
