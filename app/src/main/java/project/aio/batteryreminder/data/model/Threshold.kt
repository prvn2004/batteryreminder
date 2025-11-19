package project.aio.batteryreminder.data.model

data class Threshold(
    val percentage: Int,
    val type: ThresholdType = ThresholdType.BOTH // For future expansion (Low/High specific)
)

enum class ThresholdType { LOW, HIGH, BOTH }