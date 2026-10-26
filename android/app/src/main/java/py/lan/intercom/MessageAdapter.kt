package py.lan.intercom

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de mensajes estilo chat: entrantes a la izquierda, salientes a la derecha. */
class MessageAdapter(private val onOpenFile: (IntercomService.Msg) -> Unit) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = ArrayList<IntercomService.Msg>()
    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(list: List<IntercomService.Msg>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val out = m.dir == "out"
        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (out) Gravity.END else Gravity.START
        holder.bubble.layoutParams = lp
        holder.bubble.setBackgroundResource(if (out) R.drawable.bubble_out else R.drawable.bubble_in)

        val who = if (out) "vos → ${m.peerName}" else m.peerName
        holder.who.text = "$who · ${hora.format(Date(m.at))}" + if (!m.ok) "  ⚠ ${m.error}" else ""

        val abrible = m.kind == "file" && m.uri.isNotEmpty()
        holder.body.text = when (m.kind) {
            "file" -> "📄 ${m.fileName}" + if (abrible) "\n(tocá para abrir)" else ""
            else -> m.text
        }
        if (abrible) {
            holder.bubble.setOnClickListener { onOpenFile(m) }
        } else {
            holder.bubble.setOnClickListener(null)
            holder.bubble.isClickable = false
        }
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val who: TextView = v.findViewById(R.id.who)
        val body: TextView = v.findViewById(R.id.body)
    }
}
