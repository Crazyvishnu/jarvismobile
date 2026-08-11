package com.vishnu.jarvis

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<String>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val aiText: TextView = view.findViewById(R.id.aiMessageText)
        val userText: TextView = view.findViewById(R.id.userMessageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]
        if (msg.startsWith("You:") || msg.startsWith("You: ")) {
            holder.userText.visibility = View.VISIBLE
            holder.aiText.visibility = View.GONE
            holder.userText.text = msg.removePrefix("You:").trim()
        } else {
            holder.aiText.visibility = View.VISIBLE
            holder.userText.visibility = View.GONE
            holder.aiText.text = msg.removePrefix("Jarvis:").trim()
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: String) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }
}
