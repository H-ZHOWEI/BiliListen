package com.bilisten.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bilisten.app.R
import com.bilisten.app.data.BiliRepository
import com.bilisten.app.databinding.FragmentFoldersBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class FolderFragment : Fragment(), MainActivity.Reloadable {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderAdapter { folder ->
            (activity as? MainActivity)?.openFolder(folder)
        }
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = adapter
        binding.swipe.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.bili_pink)
        )
        binding.swipe.setOnRefreshListener { load() }
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun reload() = load()

    private fun load() {
        if (_binding == null) return
        binding.loading.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val folders = BiliRepository.fetchFolders(com.bilisten.app.data.Prefs.mid)
                adapter.submitList(folders)
                binding.empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.text = getString(R.string.empty_folders)

                // 「created/list-all」返回的 media_count 经常不准（含失效视频/缓存滞后），
                // 这里并发用资源列表接口的 info.media_count 校准一遍，跟B站App显示对齐
                if (folders.isNotEmpty()) {
                    val corrected = kotlinx.coroutines.coroutineScope {
                        folders.map { f ->
                            async {
                                try {
                                    val info = BiliRepository.fetchFavPage(f.id, 1).info
                                    val real = info?.mediaCount ?: f.mediaCount
                                    if (real != f.mediaCount) f.copy(mediaCount = real) else f
                                } catch (e: Exception) {
                                    f
                                }
                            }
                        }.awaitAll()
                    }
                    _binding?.let { b ->
                        b.loading.visibility = View.GONE
                        adapter.submitList(corrected)
                    }
                }
            } catch (e: Exception) {
                binding.empty.visibility = View.VISIBLE
                binding.emptyText.text = "加载失败：${e.message}\n下拉可重试"
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }
}
