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
    // Changed String imageUrl to Int imageRes
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

    // State for permission page
    var permissionsState = Triple(false, false, false)
        set(value) {
            field = value
            // Permission page is always last
            notifyItemChanged(items.size - 1)
        }

    sealed class ActionType {
        data class Permission(val type: PermissionType) : ActionType()
        data class OpenUrl(val url: String) : ActionType()
    }

    enum class PermissionType { NOTIFICATIONS, OVERLAY, SETTINGS }

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
            // Load local resource directly
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
            val (notifsGranted, overlayGranted, settingsGranted) = permissionsState

            // Notifications
            binding.btnGrantNotifs.setOnClickListener { onAction(ActionType.Permission(PermissionType.NOTIFICATIONS)) }
            binding.btnGrantNotifs.alpha = if (notifsGranted) 0.5f else 1.0f
            binding.ivCheckNotifs.visibility = if (notifsGranted) View.VISIBLE else View.GONE
            binding.tvGrantNotifs.visibility = if (notifsGranted) View.GONE else View.VISIBLE

            // Overlay
            binding.btnGrantOverlay.setOnClickListener { onAction(ActionType.Permission(PermissionType.OVERLAY)) }
            binding.btnGrantOverlay.alpha = if (overlayGranted) 0.5f else 1.0f
            binding.ivCheckOverlay.visibility = if (overlayGranted) View.VISIBLE else View.GONE
            binding.tvGrantOverlay.visibility = if (overlayGranted) View.GONE else View.VISIBLE

            // Settings
            binding.btnGrantSettings.setOnClickListener { onAction(ActionType.Permission(PermissionType.SETTINGS)) }
            binding.btnGrantSettings.alpha = if (settingsGranted) 0.5f else 1.0f
            binding.ivCheckSettings.visibility = if (settingsGranted) View.VISIBLE else View.GONE
            binding.tvGrantSettings.visibility = if (settingsGranted) View.GONE else View.VISIBLE
        }
    }
}