package com.georgernstgraf.polishedrecognition.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.R

/**
 * #212: the searchable model dropdown adapter, extracted unchanged from the
 * old SettingsActivity inner class — the ProvidersActivity needs the exact
 * same filtering contract (tap shows all, typing filters, exact match never
 * shrinks the list).
 */
class ModelFilterAdapter(items: List<String>) : BaseAdapter(), Filterable {
    val allItems: List<String> = items.toList()
    private var displayItems: List<String> = items.toList()
    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val results = FilterResults()
            val query = constraint?.toString()?.trim()?.lowercase()
            // The view's internal focus-triggered filter runs async and lands after the
            // click handler's showAll() — with the field holding the selected model, it
            // would shrink the list to that single entry. Returning the full list for an
            // exact match keeps every trigger path (focus, click, full-ID typing) at "all".
            val filtered = if (query.isNullOrBlank() || allItems.any { it.equals(query, ignoreCase = true) }) {
                allItems
            } else {
                allItems.filter { it.lowercase().contains(query) }
            }
            results.values = filtered
            results.count = filtered.size
            return results
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val filtered = results?.values as? List<String> ?: return
            displayItems = filtered.toList()
            notifyDataSetChanged()
        }
    }

    fun showAll() {
        displayItems = allItems.toList()
        notifyDataSetChanged()
    }

    override fun getCount(): Int = displayItems.size
    override fun getItem(position: Int): Any = displayItems[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView as? TextView ?: LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false) as TextView
        view.text = displayItems[position]
        return view
    }

    override fun getFilter(): Filter = filter
}
