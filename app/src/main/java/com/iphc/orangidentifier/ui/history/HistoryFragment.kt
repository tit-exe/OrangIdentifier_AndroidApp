package com.iphc.orangidentifier.ui.history

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.ui.MainViewModel
import com.iphc.orangidentifier.ui.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HistoryFragment : BaseFragment(R.layout.fragment_history) {

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ScanHistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_history)
        tvEmpty = view.findViewById(R.id.tv_empty)

        adapter = ScanHistoryAdapter { scan ->
            findNavController().navigate(
                R.id.nav_scan_result,
                bundleOf("scanId" to scan.id)
            )
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanHistory.collect { scans ->
                    adapter.submitList(scans)
                    tvEmpty.visibility = if (scans.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
