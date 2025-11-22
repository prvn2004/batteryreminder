// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\ui\onboarding\OnboardingActivity.kt =====
package project.aio.batteryreminder.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import project.aio.batteryreminder.R
import project.aio.batteryreminder.data.PreferencesManager
import project.aio.batteryreminder.databinding.ActivityOnboardingBinding
import project.aio.batteryreminder.ui.MainActivity
import project.aio.batteryreminder.utils.AutoStartHelper // Import the helper
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {

    @Inject lateinit var preferencesManager: PreferencesManager
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var adapter: OnboardingAdapter

    private val githubUrl = "https://github.com/prvn2004/batteryreminder"

    private val pages = listOf(
        OnboardingPage.Info(
            "Total Battery Health",
            "Monitor your device's voltage, temperature, and drain rate in real-time to keep it healthy.",
            R.drawable.battery
        ),
        OnboardingPage.Info(
            "Cable Diagnostic",
            "Not all cables are created equal. Test your charger's stability, ripple, and charging speed.",
            R.drawable.cable
        ),
        OnboardingPage.Info(
            "Smart Alerts",
            "Get notified about full charge, low battery, thermal throttling, and ghost drains while you sleep.",
            R.drawable.alert
        ),
        OnboardingPage.Privacy(githubUrl),
        OnboardingPage.Permissions
    )

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = OnboardingAdapter(pages) { action ->
            when (action) {
                is OnboardingAdapter.ActionType.Permission -> handlePermissionClick(action.type)
                is OnboardingAdapter.ActionType.OpenUrl -> openUrl(action.url)
            }
        }
        binding.viewPager.adapter = adapter

        setupIndicators()
        setCurrentIndicator(0)

        binding.btnNext.setOnClickListener {
            if (binding.viewPager.currentItem < pages.size - 1) {
                binding.viewPager.currentItem += 1
            }
        }

        binding.btnGetStarted.setOnClickListener {
            if (areAllPermissionsGranted()) {
                completeOnboarding()
            } else {
                Toast.makeText(this, "Please grant all permissions to proceed.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSkip.setOnClickListener {
            binding.viewPager.currentItem = pages.size - 1
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setCurrentIndicator(position)

                val isLastPage = position == pages.size - 1
                binding.btnNext.visibility = if (isLastPage) View.GONE else View.VISIBLE
                binding.btnGetStarted.visibility = if (isLastPage) View.VISIBLE else View.GONE
                binding.btnSkip.visibility = if (isLastPage) View.GONE else View.VISIBLE

                if (isLastPage) checkPermissionState()
            }
        })
    }

    private fun setupIndicators() {
        val indicators = arrayOfNulls<ImageView>(adapter.itemCount)
        val layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        layoutParams.setMargins(12, 0, 12, 0)

        for (i in indicators.indices) {
            indicators[i] = ImageView(applicationContext)
            indicators[i]?.apply {
                this.setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.indicator_inactive))
                this.layoutParams = layoutParams
            }
            binding.layoutDots.addView(indicators[i])
        }
    }

    private fun setCurrentIndicator(index: Int) {
        val childCount = binding.layoutDots.childCount
        for (i in 0 until childCount) {
            val imageView = binding.layoutDots.getChildAt(i) as ImageView
            if (i == index) {
                imageView.setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.indicator_active))
            } else {
                imageView.setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.indicator_inactive))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionState()
    }

    @SuppressLint("BatteryLife")
    private fun handlePermissionClick(type: OnboardingAdapter.PermissionType) {
        when (type) {
            OnboardingAdapter.PermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    checkPermissionState()
                }
            }
            OnboardingAdapter.PermissionType.OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
            OnboardingAdapter.PermissionType.SETTINGS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
            OnboardingAdapter.PermissionType.BATTERY_OPTIMIZATION -> {
                // 1. Standard Whitelist
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    try { startActivity(intent) } catch (e: Exception) {}
                }

                // 2. OEM Specific (Crucial for Motorola/Xiaomi)
                AutoStartHelper.getAutoStartPermission(this)
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionState() {
        val notifs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val settings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(this) else true

        val batteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        adapter.permissionsState = OnboardingAdapter.PermissionsState(notifs, overlay, settings, batteryOpt)

        val allGranted = notifs && overlay && settings && batteryOpt
        binding.btnGetStarted.alpha = if (allGranted) 1.0f else 0.5f
        binding.btnGetStarted.isEnabled = allGranted
    }

    private fun areAllPermissionsGranted(): Boolean {
        val state = adapter.permissionsState
        return state.notifications && state.overlay && state.settings && state.batteryOpt
    }

    private fun completeOnboarding() {
        lifecycleScope.launch {
            preferencesManager.setFirstRunCompleted()
            startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
            finish()
        }
    }
}