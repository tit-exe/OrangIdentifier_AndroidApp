package com.iphc.orangidentifier.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.data.repository.GalleryManager

class GalleryAdapter(
    private val onDelete:    (GalleryManager.IndividualSummary) -> Unit,
    private val onShare:     (GalleryManager.IndividualSummary) -> Unit,
    private val onAddPhotos: (GalleryManager.IndividualSummary) -> Unit
) : ListAdapter<GalleryManager.IndividualSummary, GalleryAdapter.ViewHolder>(Differ) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:      TextView    = view.findViewById(R.id.tv_name)
        val tvMeta:      TextView    = view.findViewById(R.id.tv_meta)
        val btnAddPhotos:ImageButton = view.findViewById(R.id.btn_add_photos)
        val btnShare:    ImageButton = view.findViewById(R.id.btn_share)
        val btnDelete:   ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_individual, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvMeta.text = buildMeta(item)
        holder.btnAddPhotos.setOnClickListener { onAddPhotos(item) }
        holder.btnDelete.setOnClickListener    { onDelete(item) }
        holder.btnShare.setOnClickListener     { onShare(item) }
    }

    private fun buildMeta(item: GalleryManager.IndividualSummary): String {
        val anchor = item.numCrops?.let { "$it crops" } ?: "Bundled"
        val field  = if (item.fieldCrops > 0) " +${item.fieldCrops} field" else ""
        val date   = item.addedAt?.take(10) ?: "—"
        return "$anchor$field · $date"
    }

    private object Differ : DiffUtil.ItemCallback<GalleryManager.IndividualSummary>() {
        override fun areItemsTheSame(a: GalleryManager.IndividualSummary,
                                     b: GalleryManager.IndividualSummary) = a.name == b.name
        override fun areContentsTheSame(a: GalleryManager.IndividualSummary,
                                        b: GalleryManager.IndividualSummary) = a == b
    }
}
