package com.vishnu.jarvis

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<String>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = messages[position]
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: String) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }
}
