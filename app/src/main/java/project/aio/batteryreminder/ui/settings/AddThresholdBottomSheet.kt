package project.aio.batteryreminder.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import project.aio.batteryreminder.databinding.SheetAddThresholdBinding

class AddThresholdBottomSheet(private val onAdd: (Int) -> Unit) : BottomSheetDialogFragment() {

    private var _binding: SheetAddThresholdBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetAddThresholdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var selectedValue = 50

        binding.sliderValue.addOnChangeListener { _, value, _ ->
            selectedValue = value.toInt()
            binding.tvValue.text = "$selectedValue%"
        }

        binding.btnAdd.setOnClickListener {
            onAdd(selectedValue)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}