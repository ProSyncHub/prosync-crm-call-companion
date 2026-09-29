package com.prosync.crmcompanion

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/** Suggests employees whose name, email or department contains the typed text anywhere. */
class EmployeeSuggestAdapter(context: Context, private val all: List<EmployeeOption>) :
    ArrayAdapter<EmployeeOption>(context, android.R.layout.simple_dropdown_item_1line, all.toMutableList()) {

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.substringBefore(" •")?.trim().orEmpty()
            val matches = if (query.isEmpty()) all else all.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.email.contains(query, ignoreCase = true) ||
                    it.department.contains(query, ignoreCase = true)
            }
            return FilterResults().apply { values = matches; count = matches.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            clear()
            addAll((results?.values as? List<EmployeeOption>).orEmpty())
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?) = (resultValue as? EmployeeOption)?.name ?: ""
    }

    override fun getFilter(): Filter = filter
}
