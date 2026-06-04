package com.iphc.orangidentifier.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.utils.toFormattedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanHistoryAdapter(
    private val onClick: (ScanRecord) -> Unit
) : ListAdapter<ScanRecord, ScanHistoryAdapter.ViewHolder>(ScanDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_history, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // Cancel in-flight bitmap load as soon as the view leaves the screen.
    // onViewDetachedFromWindow fires when scrolled off; onViewRecycled fires later
    // when the view is actually recycled. Both are needed: detach for prompt cancellation,
    // recycle to clear the ImageView reference before the view is reused.
    // Cancel load immediately when scrolled off screen, and clear the bitmap when recycled
    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.cancelLoad()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()
    }

    class ViewHolder(
        view: View,
        private val onClick: (ScanRecord) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val ivThumb:  ImageView = view.findViewById(R.id.iv_thumb)
        private val tvDate:   TextView  = view.findViewById(R.id.tv_date)
        private val tvResult: TextView  = view.findViewById(R.id.tv_result)
        private val tvFaces:  TextView  = view.findViewById(R.id.tv_faces)

        // Per-ViewHolder scope — independent of LifecycleOwner so it always works,
        // even when findViewTreeLifecycleOwner() returns null (e.g. first bind before attach).
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun clearImage() {
            cancelLoad()
            ivThumb.setImageBitmap(null)
        }

        fun bind(scan: ScanRecord) {
            loadJob?.cancel()
            ivThumb.setImageBitmap(null)

            loadJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeFile(
                            scan.imagePath,
                            BitmapFactory.Options().apply { inSampleSize = 4 }
                        )
                    }.getOrNull()
                }
                ivThumb.setImageBitmap(bmp)
            }

            tvDate.text = scan.timestamp.toFormattedDateTime()

            val faceCount = scan.detections.size
            tvFaces.text = if (faceCount == 1) "1 face" else "$faceCount faces"

            val primaryDetection = scan.detections
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
                .firstOrNull()

            tvResult.text = when {
                primaryDetection == null       -> "No faces"
                primaryDetection.isUnknown     -> "Unknown"
                else -> primaryDetection.topPredictions.firstOrNull()?.individualName ?: "Unknown"
            }

            itemView.setOnClickListener { onClick(scan) }
        }
    }

    class ScanDiffCallback : DiffUtil.ItemCallback<ScanRecord>() {
        override fun areItemsTheSame(oldItem: ScanRecord, newItem: ScanRecord) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ScanRecord, newItem: ScanRecord) =
            oldItem == newItem
    }
}
