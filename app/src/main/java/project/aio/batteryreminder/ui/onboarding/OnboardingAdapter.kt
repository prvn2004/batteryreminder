package project.aio.batteryreminder.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import project.aio.batteryreminder.R
import project.aio.batteryreminder.databinding.ItemOnboardingPageBinding
import project.aio.batteryreminder.databinding.ItemOnboardingPermissionsBinding
import project.aio.batteryreminder.databinding.ItemOnboardingPrivacyBinding

sealed class OnboardingPage {
    data class Info(val title: String, val desc: String, val imageRes: Int) : OnboardingPage()
    data class Privacy(val githubUrl: String) : OnboardingPage()
    object Permissions : OnboardingPage()
}

class OnboardingAdapter(
    private val items: List<OnboardingPage>,
    private val onAction: (ActionType) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_INFO = 1
        const val VIEW_TYPE_PRIVACY = 2
        const val VIEW_TYPE_PERMISSIONS = 3
    }

    // Quadruple state now: (Notifs, Overlay, Settings, BatteryOpt)
    // Using a data class or simple List is cleaner, but sticking to structure:
    var permissionsState = PermissionsState()
        set(value) {
            field = value
            notifyItemChanged(items.size - 1)
        }

    data class PermissionsState(
        val notifications: Boolean = false,
        val overlay: Boolean = false,
        val settings: Boolean = false,
        val batteryOpt: Boolean = false
    )

    sealed class ActionType {
        data class Permission(val type: PermissionType) : ActionType()
        data class OpenUrl(val url: String) : ActionType()
    }

    enum class PermissionType { NOTIFICATIONS, OVERLAY, SETTINGS, BATTERY_OPTIMIZATION }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is OnboardingPage.Info -> VIEW_TYPE_INFO
            is OnboardingPage.Privacy -> VIEW_TYPE_PRIVACY
            is OnboardingPage.Permissions -> VIEW_TYPE_PERMISSIONS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_INFO -> InfoViewHolder(
                ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            VIEW_TYPE_PRIVACY -> PrivacyViewHolder(
                ItemOnboardingPrivacyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> PermissionsViewHolder(
                ItemOnboardingPermissionsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is InfoViewHolder -> holder.bind(items[position] as OnboardingPage.Info)
            is PrivacyViewHolder -> holder.bind(items[position] as OnboardingPage.Privacy)
            is PermissionsViewHolder -> holder.bind()
        }
    }

    override fun getItemCount() = items.size

    inner class InfoViewHolder(val binding: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: OnboardingPage.Info) {
            binding.tvTitle.text = item.title
            binding.tvDesc.text = item.desc
            binding.ivImage.setImageResource(item.imageRes)
        }
    }

    inner class PrivacyViewHolder(val binding: ItemOnboardingPrivacyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: OnboardingPage.Privacy) {
            binding.btnGithub.setOnClickListener {
                onAction(ActionType.OpenUrl(item.githubUrl))
            }
        }
    }

    inner class PermissionsViewHolder(val binding: ItemOnboardingPermissionsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            val state = permissionsState

            // Notifications
            binding.btnGrantNotifs.setOnClickListener { onAction(ActionType.Permission(PermissionType.NOTIFICATIONS)) }
            binding.btnGrantNotifs.alpha = if (state.notifications) 0.5f else 1.0f
            binding.ivCheckNotifs.visibility = if (state.notifications) View.VISIBLE else View.GONE
            binding.tvGrantNotifs.visibility = if (state.notifications) View.GONE else View.VISIBLE

            // Overlay
            binding.btnGrantOverlay.setOnClickListener { onAction(ActionType.Permission(PermissionType.OVERLAY)) }
            binding.btnGrantOverlay.alpha = if (state.overlay) 0.5f else 1.0f
            binding.ivCheckOverlay.visibility = if (state.overlay) View.VISIBLE else View.GONE
            binding.tvGrantOverlay.visibility = if (state.overlay) View.GONE else View.VISIBLE

            // Settings
            binding.btnGrantSettings.setOnClickListener { onAction(ActionType.Permission(PermissionType.SETTINGS)) }
            binding.btnGrantSettings.alpha = if (state.settings) 0.5f else 1.0f
            binding.ivCheckSettings.visibility = if (state.settings) View.VISIBLE else View.GONE
            binding.tvGrantSettings.visibility = if (state.settings) View.GONE else View.VISIBLE

            // Battery Optimization
            binding.btnGrantBattery.setOnClickListener { onAction(ActionType.Permission(PermissionType.BATTERY_OPTIMIZATION)) }
            binding.btnGrantBattery.alpha = if (state.batteryOpt) 0.5f else 1.0f
            binding.ivCheckBattery.visibility = if (state.batteryOpt) View.VISIBLE else View.GONE
            binding.tvGrantBattery.visibility = if (state.batteryOpt) View.GONE else View.VISIBLE
        }
    }
}