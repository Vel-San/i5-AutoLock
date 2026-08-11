package com.i5autolock.data.bluelink.eu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire DTOs for the EU CCS API. Only the fields we consume are declared. */

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
data class VehiclesEnvelope(
    val resMsg: VehiclesResMsg? = null,
)

@Serializable
data class VehiclesResMsg(
    val vehicles: List<VehicleDto> = emptyList(),
)

@Serializable
data class VehicleDto(
    val vehicleId: String,
    val vin: String = "",
    val nickname: String = "",
    val vehicleName: String = "",
    val type: String = "",
    val regDate: String? = null,
)

@Serializable
data class StatusEnvelope(
    val resMsg: StatusResMsg? = null,
)

@Serializable
data class StatusResMsg(
    val vehicleStatusInfo: VehicleStatusInfoDto? = null,
    val doorLock: Boolean? = null,
    val engine: Boolean? = null,
)

@Serializable
data class VehicleStatusInfoDto(
    val vehicleStatus: VehicleStatusDto? = null,
)

@Serializable
data class VehicleStatusDto(
    val doorLock: Boolean? = null,
    val engine: Boolean? = null,
    val airCtrlOn: Boolean? = null,
    val time: String? = null,
    val evStatus: EvStatusDto? = null,
    val battery: BatteryDto? = null,
    val doorOpen: DoorOpenDto? = null,
)

/** Device registration response — yields the CCSP deviceId used on all EU calls. */
@Serializable
data class RegisterEnvelope(
    val resMsg: DeviceIdHolder? = null,
    val retValue: DeviceIdHolder? = null,
    val deviceId: String? = null,
)

@Serializable
data class DeviceIdHolder(val deviceId: String? = null)

/** PIN verification response — yields the short-lived control token for lock/unlock. */
@Serializable
data class PinEnvelope(
    val controlToken: String? = null,
    val resMsg: ControlTokenHolder? = null,
    val retValue: ControlTokenHolder? = null,
)

@Serializable
data class ControlTokenHolder(val controlToken: String? = null)

/** IDP RSA public key (JWK) used to encrypt the password for headless login. */
@Serializable
data class CertsEnvelope(val retValue: JwkDto? = null)

@Serializable
data class JwkDto(val kid: String = "", val n: String = "", val e: String = "")

@Serializable
data class EvStatusDto(
    val batteryStatus: Int? = null,
    val drvDistance: List<DrvDistanceDto> = emptyList(),
)

@Serializable
data class DrvDistanceDto(
    val rangeByFuel: RangeByFuelDto? = null,
)

@Serializable
data class RangeByFuelDto(
    val totalAvailableRange: RangeValueDto? = null,
)

@Serializable
data class RangeValueDto(
    val value: Int? = null,
)

@Serializable
data class BatteryDto(
    @SerialName("batSoc") val batterySoc: Int? = null,
)

@Serializable
data class DoorOpenDto(
    val frontLeft: Int? = null,
    val frontRight: Int? = null,
    val backLeft: Int? = null,
    val backRight: Int? = null,
)
