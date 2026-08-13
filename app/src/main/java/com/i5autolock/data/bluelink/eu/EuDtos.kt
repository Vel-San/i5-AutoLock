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
    val ccuCCS2ProtocolSupport: Int? = null,
    val protocolType: Int? = null,
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

// ── CCS2 status DTOs (Ioniq 5 / EV6 / IONIQ 6 use these instead of the v1 shape) ─────────
//
// Response shape (only the fields we care about):
// {"resMsg": {"state": {"Vehicle": {
//   "Cabin":       {"Door": {"Row1": {"Driver": {"Lock": 1, "Open": 0}, "Passenger": {...}}, "Row2": {...}},
//                   "HVAC": {...}},
//   "Drivetrain":  {"FuelSystem": {"DTE": {"EV": 234, "Total": 234, "Unit": 1}, "IgnitionStatus": "Off"}},
//   "Green":       {"BatteryManagement": {"BatteryRemain": {"Ratio": 87}},
//                   "ChargingInformation": {"Charging": {"RemainTime": ...}}},
//   "Electronics": {"Battery": {"Level": 78}}
// }}}}
// NB: DTE is flat — Total/EV are numeric ranges, Unit is a sibling (1=km, 3=miles).

@Serializable
data class Ccs2StatusEnvelope(val resMsg: Ccs2ResMsg? = null)

@Serializable
data class Ccs2ResMsg(val state: Ccs2State? = null)

@Serializable
data class Ccs2State(
    @SerialName("Vehicle") val vehicle: Ccs2Vehicle? = null,
    @SerialName("Date") val date: String? = null,
)

@Serializable
data class Ccs2Vehicle(
    @SerialName("Cabin") val cabin: Ccs2Cabin? = null,
    @SerialName("Drivetrain") val drivetrain: Ccs2Drivetrain? = null,
    @SerialName("Green") val green: Ccs2Green? = null,
    @SerialName("Electronics") val electronics: Ccs2Electronics? = null,
)

@Serializable
data class Ccs2Cabin(
    @SerialName("Door") val door: Ccs2Door? = null,
    @SerialName("HVAC") val hvac: Ccs2Hvac? = null,
)

@Serializable
data class Ccs2Door(
    @SerialName("Row1") val row1: Ccs2DoorRow? = null,
    @SerialName("Row2") val row2: Ccs2DoorRow? = null,
)

@Serializable
data class Ccs2DoorRow(
    @SerialName("Driver") val driver: Ccs2DoorState? = null,
    @SerialName("Passenger") val passenger: Ccs2DoorState? = null,
    @SerialName("Left") val left: Ccs2DoorState? = null,
    @SerialName("Right") val right: Ccs2DoorState? = null,
)

@Serializable
data class Ccs2DoorState(
    @SerialName("Lock") val lock: Int? = null,
    @SerialName("Open") val open: Int? = null,
)

@Serializable
data class Ccs2Hvac(
    @SerialName("Row1") val row1: Ccs2HvacRow? = null,
)

@Serializable
data class Ccs2HvacRow(
    @SerialName("HVAC") val hvac: Ccs2HvacStatus? = null,
)

@Serializable
data class Ccs2HvacStatus(
    @SerialName("Active") val active: Int? = null,
)

@Serializable
data class Ccs2Drivetrain(
    @SerialName("FuelSystem") val fuelSystem: Ccs2FuelSystem? = null,
)

@Serializable
data class Ccs2FuelSystem(
    @SerialName("DTE") val dte: Ccs2Dte? = null,
    @SerialName("IgnitionStatus") val ignitionStatus: String? = null,
)

@Serializable
data class Ccs2Dte(
    // Real CCS2 shape (Ioniq 5): {"EV":234,"Total":234,"Unit":1} — flat numbers, NOT a {Value,Unit} object.
    @SerialName("Total") val total: Double? = null,
    @SerialName("EV") val ev: Double? = null,
    @SerialName("Unit") val unit: Int? = null,
)

@Serializable
data class Ccs2Green(
    @SerialName("BatteryManagement") val batteryManagement: Ccs2BatteryManagement? = null,
    @SerialName("ChargingInformation") val chargingInformation: Ccs2ChargingInformation? = null,
    @SerialName("DrivingReady") val drivingReady: Int? = null,
)

@Serializable
data class Ccs2BatteryManagement(
    @SerialName("BatteryRemain") val batteryRemain: Ccs2BatteryRemain? = null,
)

@Serializable
data class Ccs2BatteryRemain(
    @SerialName("Ratio") val ratio: Double? = null,
)

@Serializable
data class Ccs2ChargingInformation(
    @SerialName("Charging") val charging: Ccs2Charging? = null,
)

@Serializable
data class Ccs2Charging(
    @SerialName("RemainTime") val remainTime: Double? = null,
)

@Serializable
data class Ccs2Electronics(
    @SerialName("Battery") val battery: Ccs2AuxBattery? = null,
)

@Serializable
data class Ccs2AuxBattery(
    @SerialName("Level") val level: Int? = null,
)
