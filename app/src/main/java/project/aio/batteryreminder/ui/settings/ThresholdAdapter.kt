package project.aio.batteryreminder.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import project.aio.batteryreminder.data.model.Threshold
import project.aio.batteryreminder.databinding.ItemThresholdBinding

class ThresholdAdapter(private val onDelete: (Threshold) -> Unit) :
    ListAdapter<Threshold, ThresholdAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemThresholdBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemThresholdBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvPercentage.text = "${item.percentage}%"
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    class DiffCallback : DiffUtil.ItemCallback<Threshold>() {
        override fun areItemsTheSame(oldItem: Threshold, newItem: Threshold) = oldItem.percentage == newItem.percentage
        override fun areContentsTheSame(oldItem: Threshold, newItem: Threshold) = oldItem == newItem
    }
}