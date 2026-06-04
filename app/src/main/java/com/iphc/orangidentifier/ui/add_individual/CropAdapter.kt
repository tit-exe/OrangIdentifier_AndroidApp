package com.iphc.orangidentifier.ui.add_individual

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iphc.orangidentifier.R

/**
 * RecyclerView adapter for the crop review grid/strip.
 *
 * @param onDelete  Called when the ✕ button is tapped.
 * @param onEdit    Called when the crop image itself is tapped (opens crop editor).
 *                  Pass null to hide the edit affordance (used in horizontal preview strip).
 */
class CropAdapter(
    private val onDelete: (CropItem) -> Unit,
    private val onEdit:   ((CropItem) -> Unit)? = null
) : ListAdapter<CropItem, CropAdapter.CropViewHolder>(Differ) {

    inner class CropViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCrop:    ImageView   = view.findViewById(R.id.iv_crop)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_crop)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CropViewHolder =
        CropViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crop, parent, false)
        )

    override fun onViewRecycled(holder: CropViewHolder) {
        super.onViewRecycled(holder)
        // Release the bitmap reference so it can be GC'd before the new one is loaded
        holder.ivCrop.setImageBitmap(null)
    }

    override fun onBindViewHolder(holder: CropViewHolder, position: Int) {
        val item = getItem(position)

        // Crops are small local JPEGs (~50–100 KB) — synchronous decode is acceptable here
        val bmp = BitmapFactory.decodeFile(item.cropPath)
        holder.ivCrop.setImageBitmap(bmp)

        holder.btnDelete.setOnClickListener { onDelete(item) }

        if (onEdit != null) {
            // Tapping the crop image opens the resize editor
            holder.ivCrop.setOnClickListener { onEdit.invoke(item) }
            // Visual hint: slightly dim to indicate it's tappable
            holder.ivCrop.alpha = 0.92f
        } else {
            holder.ivCrop.setOnClickListener(null)
            holder.ivCrop.alpha = 1f
        }
    }

    private object Differ : DiffUtil.ItemCallback<CropItem>() {
        override fun areItemsTheSame(old: CropItem, new: CropItem) = old.cropPath == new.cropPath
        override fun areContentsTheSame(old: CropItem, new: CropItem) = old == new
    }
}
