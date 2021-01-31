package com.github.vkay94.timebar.example.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.vkay94.timebar.example.databinding.FragmentTimebarBinding

/**
 * A placeholder fragment containing a simple view.
 */
class TimeBarAdjustmentsFragment : Fragment() {

    private var _binding: FragmentTimebarBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimebarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.let { fa ->
            viewModel = ViewModelProvider(fa).get(MainViewModel::class.java)

            binding.switchShowChapters.setOnCheckedChangeListener { _, isChecked ->
                viewModel.tbShowChapters.value = isChecked
            }

            binding.switchShowSegments.setOnCheckedChangeListener { _, isChecked ->
                viewModel.tbShowSegments.value = isChecked
            }

            binding.switchUsePreview.setOnCheckedChangeListener { _, isChecked ->
                viewModel.tbUsePreview.value = isChecked
            }

            binding.switchChapterVibrate.isChecked = false
            binding.switchChapterVibrate.setOnCheckedChangeListener { _, isChecked ->
                viewModel.tbVibrateChapter.value = isChecked
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): TimeBarAdjustmentsFragment {
            return TimeBarAdjustmentsFragment()
        }
    }
}