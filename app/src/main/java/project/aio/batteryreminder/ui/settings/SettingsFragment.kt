package project.aio.batteryreminder.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import project.aio.batteryreminder.R
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.data.model.Threshold
import project.aio.batteryreminder.databinding.FragmentSettingsBinding
import project.aio.batteryreminder.ui.overlay.OverlayService
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var overlayService: OverlayService

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ThresholdAdapter
    private var currentThresholds = mutableListOf<Threshold>()

    private val soundPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let {
                lifecycleScope.launch {
                    preferencesManager.updateSoundUri(it.toString())
                    Toast.makeText(context, "Sound Updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle Insets (Margins for Status Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupRecyclerView() {
        adapter = ThresholdAdapter { threshold ->
            deleteThreshold(threshold)
        }
        binding.rvThresholds.layoutManager = LinearLayoutManager(context)
        binding.rvThresholds.adapter = adapter
    }

    private fun setupListeners() {
        // ADD THRESHOLD (BottomSheet)
        binding.btnAddThreshold.setOnClickListener { showAddBottomSheet() }

        // DURATION CHIPS
        binding.chipGroupDuration.setOnCheckedChangeListener { _, checkedId ->
            val seconds = when(checkedId) {
                R.id.chip3s -> 3
                R.id.chip10s -> 10
                R.id.chip30s -> 30
                R.id.chipInfinite -> -1
                else -> 30
            }
            lifecycleScope.launch { preferencesManager.updateAlertDuration(seconds) }
        }

        // TOGGLES
        binding.cardSound.setOnClickListener {
            val currentState = binding.ivSoundCheck.isVisible
            lifecycleScope.launch { preferencesManager.updateSound(!currentState) }
        }

        binding.btnSelectSound.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alert Sound")
            }
            soundPickerLauncher.launch(intent)
        }

        binding.cardFlash.setOnClickListener {
            lifecycleScope.launch { preferencesManager.updateFlash(!binding.ivFlashCheck.isVisible) }
        }

        binding.cardVib.setOnClickListener {
            lifecycleScope.launch { preferencesManager.updateVibration(!binding.ivVibCheck.isVisible) }
        }

        binding.cardTts.setOnClickListener {
            lifecycleScope.launch { preferencesManager.updateTts(!binding.ivTtsCheck.isVisible) }
        }

        // DIAGNOSTIC TEST (Overlay)
        binding.btnTestAlert.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                overlayService.showOverlay("SYSTEM TEST\nDIAGNOSTIC MODE", 15)
            } else {
                Toast.makeText(context, "Grant Overlay Permission First", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
                startActivity(intent)
            }
        }
    }

    private fun setupObservers() {
        // Thresholds
        lifecycleScope.launch {
            preferencesManager.thresholds.collect {
                currentThresholds = it.toMutableList()
                adapter.submitList(it)
                updateAddButtonState()
            }
        }

        // Duration
        lifecycleScope.launch {
            preferencesManager.alertDuration.collect { duration ->
                val chipId = when(duration) {
                    3 -> R.id.chip3s
                    10 -> R.id.chip10s
                    30 -> R.id.chip30s
                    -1 -> R.id.chipInfinite
                    else -> R.id.chip30s
                }
                binding.chipGroupDuration.check(chipId)
            }
        }

        // Toggles
        lifecycleScope.launch {
            preferencesManager.soundEnabled.collect {
                updateCardState(binding.cardSound, binding.ivSoundCheck, it)
                binding.btnSelectSound.isEnabled = it
                binding.btnSelectSound.alpha = if(it) 1.0f else 0.5f
            }
        }
        lifecycleScope.launch {
            preferencesManager.flashEnabled.collect { updateCardState(binding.cardFlash, binding.ivFlashCheck, it) }
        }
        lifecycleScope.launch {
            preferencesManager.vibrationEnabled.collect { updateCardState(binding.cardVib, binding.ivVibCheck, it) }
        }
        lifecycleScope.launch {
            preferencesManager.ttsEnabled.collect { updateCardState(binding.cardTts, binding.ivTtsCheck, it) }
        }
    }

    private fun updateCardState(card: View, checkIcon: View, isActive: Boolean) {
        checkIcon.isVisible = isActive
        card.setBackgroundResource(if (isActive) R.drawable.bg_card_filled else R.drawable.bg_card_outlined)

        val container = card as? ViewGroup
        changeTextColorRecursively(container, isActive)
    }

    private fun changeTextColorRecursively(viewGroup: ViewGroup?, isFilled: Boolean) {
        viewGroup?.let { vg ->
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                if (child is android.widget.TextView) {
                    child.setTextColor(if (isFilled) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                } else if (child is android.widget.ImageView) {
                    child.setColorFilter(if (isFilled) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                } else if (child is ViewGroup) {
                    changeTextColorRecursively(child, isFilled)
                }
            }
        }
    }

    private fun showAddBottomSheet() {
        if (currentThresholds.size >= 5) {
            Toast.makeText(context, "Max 5 thresholds allowed", Toast.LENGTH_SHORT).show()
            return
        }
        AddThresholdBottomSheet { value ->
            addThreshold(value)
        }.show(parentFragmentManager, "AddThreshold")
    }

    private fun addThreshold(value: Int) {
        if (currentThresholds.any { it.percentage == value }) {
            Toast.makeText(context, "Threshold already exists", Toast.LENGTH_SHORT).show()
            return
        }
        val newList = currentThresholds.toMutableList().apply { add(Threshold(value)) }
        newList.sortByDescending { it.percentage }
        lifecycleScope.launch { preferencesManager.updateThresholds(newList) }
    }

    private fun deleteThreshold(item: Threshold) {
        val newList = currentThresholds.toMutableList().apply { remove(item) }
        lifecycleScope.launch { preferencesManager.updateThresholds(newList) }
    }

    private fun updateAddButtonState() {
        binding.btnAddThreshold.isEnabled = currentThresholds.size < 5
        binding.btnAddThreshold.alpha = if (currentThresholds.size < 5) 1.0f else 0.5f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}