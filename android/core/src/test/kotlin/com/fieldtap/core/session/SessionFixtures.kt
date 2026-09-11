package com.fieldtap.core.session

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.core.radio.CellInfoCandidate
import com.fieldtap.core.radio.KpiCandidate
import com.fieldtap.core.radio.ServingCellIdentity
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CellRow
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.DeviceMeta
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.FixProvider
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.KpiRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import com.fieldtap.format.TrackRow
import com.fieldtap.format.TrafficRow
import com.fieldtap.format.TrafficTest
import com.fieldtap.format.TransportMeta
import java.io.File

/** Values shared by the session-core tests, modelled on the golden session (Mall walk, 311480, PCI 212). */
internal object SessionFixtures {
    /** 2026-09-10T14:30:00.000+00:00, the golden session's start. */
    const val START_WALL_MS: Long = 1_789_050_600_000L
    const val START_ELAPSED_MS: Long = 25_323_456L
    const val PID: Int = 4242
    const val DIR_NAME: String = "20260910-143000_Mall-walk-north-path"

    val P1: LatLon = LatLon(38.8895123, -77.0352671)
    val P2: LatLon = LatLon(38.8896001, -77.0350002)

    val consent: ConsentRecord = ConsentRecord(
        version = "2026-09-10-draft",
        sha256 = "85902057108ac48854a0c09cc66e7626789d3b7fb9f0c95c8ab6205520f5030f",
        grantedUtcMs = 1_789_000_000_000L,
    )

    fun identity(startedUtcMs: Long = START_WALL_MS): SessionIdentity = SessionIdentity(
        sessionId = "3f6c1a2e-8b7d-4e21-9c55-2a1f0b9d7e44",
        name = "Mall walk (north path)",
        note = "Walk-mode check of the north path, screen on, Wi-Fi off.",
        location = "National Mall, Washington DC",
        startedUtcMs = startedUtcMs,
        transport = TransportMeta(appVersion = "0.1.0", versionCode = 1),
        handset = HandsetMeta(manufacturer = "samsung", model = "SM-S921U", androidVersion = "15"),
        device = DeviceMeta(key = "app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f", label = "SM-S921U"),
        consent = consent,
    )

    fun config(policy: WritePolicy = WritePolicy()): RecorderConfig = RecorderConfig(
        identity = identity(),
        session = AllocatedSession(DIR_NAME, File("sessions", DIR_NAME), START_WALL_MS),
        pid = PID,
        writePolicy = policy,
    )

    fun event(
        timeUtcMs: Long,
        kind: EventKind,
        title: String,
        rat: EventRat = EventRat.NONE,
        severity: Severity = kind.severities.first(),
    ): EventRow = EventRow(timeUtcMs = timeUtcMs, rat = rat, kind = kind, severity = severity, title = title)

    fun kpiRow(timeEpochMs: Long, rsrpDbm: Int = -84): KpiRow = KpiRow(
        timeEpochMs = timeEpochMs,
        rat = ServingRat.LTE,
        pci = 212,
        rsrpDbm = rsrpDbm,
        rsrqDb = -10,
        sinrDb = 12,
        ageMs = 400,
        source = CellInfoSource.REQUEST,
    )

    fun kpiCandidate(measurementElapsedMs: Long, timeEpochMs: Long, rsrpDbm: Int = -84): KpiCandidate =
        KpiCandidate(kpiRow(timeEpochMs, rsrpDbm), servingIdentity(), measurementElapsedMs)

    fun servingIdentity(): ServingCellIdentity = ServingCellIdentity(
        rat = ServingRat.LTE,
        mcc = "311",
        mnc = "480",
        tac = 18704,
        cellId = 21_640_193L,
        pci = 212,
        arfcn = 66786,
        band = 66,
        bandwidthKhz = 20_000,
        operator = "Verizon",
        additionalPlmns = emptyList(),
    )

    fun cellInfoRow(seenUtcMs: Long, timeEpochMs: Long, timestampMs: Long): CellInfoRow = CellInfoRow(
        seenUtcMs = seenUtcMs,
        rat = Rat.LTE,
        registered = true,
        mcc = "311",
        mnc = "480",
        operator = "Verizon",
        pci = 212,
        arfcn = 66786,
        bands = listOf(66),
        tac = 18704,
        cellId = 21_640_193L,
        bandwidthKhz = 20_000,
        rsrp = -84,
        rsrq = -10,
        sinr = 12,
        rssi = null,
        level = 3,
        additionalPlmns = emptyList(),
        timeEpochMs = timeEpochMs,
        timestampMs = timestampMs,
        ageMs = seenUtcMs - timeEpochMs,
        stale = false,
        connectionStatus = 1,
        source = CellInfoSource.REQUEST,
        cqi = null,
        timingAdvance = null,
        csiRsrp = null,
        csiRsrq = null,
        csiSinr = null,
        screenOn = true,
        charging = false,
        wifiConnected = false,
        subId = 1,
    )

    fun cellInfoCandidate(measurementElapsedMs: Long, seenUtcMs: Long, timeEpochMs: Long): CellInfoCandidate =
        CellInfoCandidate(cellInfoRow(seenUtcMs, timeEpochMs, measurementElapsedMs), measurementElapsedMs)

    fun cellRow(samples: Int): CellRow = CellRow(
        firstSeenUtcMs = START_WALL_MS + 400,
        rat = ServingRat.LTE,
        mcc = "311",
        mnc = "480",
        tac = 18704,
        cellId = 21_640_193L,
        pci = 212,
        band = 66,
        dlEarfcn = 66786,
        ulEarfcn = 132_322,
        dlBwMhz = 20.0,
        ulBwMhz = 20.0,
        plausible = true,
        operator = "Verizon",
        additionalPlmns = emptyList(),
        samples = samples,
        rsrpMin = -97,
        rsrpMax = -84,
    )

    fun collection(fresh: Long, repeats: Long): CollectionMeta = CollectionMeta(
        medianFreshIntervalMs = 2_000,
        shortIntervalPct = 88.3,
        screenOnPct = 88.3,
        wifiConnectedPct = 0.0,
        chargingPct = 0.0,
        freshSamples = fresh,
        repeatsDropped = repeats,
        gaps = emptyList(),
    )

    /** An answer observed at [wallMs] and [elapsedMs]; the fake pipelines never look at its cells. */
    fun answer(wallMs: Long, elapsedMs: Long): CellInfoAnswer = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = emptyList(),
        subId = 1,
        conditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = false),
        observedWallMs = wallMs,
        observedElapsedMs = elapsedMs,
    )

    fun fix(elapsedMs: Long, wallMs: Long, lat: Double = 38.8895123, lon: Double = -77.0352671): FixSample = FixSample(
        elapsedMs = elapsedMs,
        wallMs = wallMs,
        lat = lat,
        lon = lon,
        accuracyM = 4.0,
        altitudeM = 18.0,
        speedMps = 1.25,
        provider = FixProvider.GPS,
        mock = false,
        observedWallMs = wallMs,
        observedElapsedMs = elapsedMs,
    )

    fun trackRow(fix: FixSample): TrackRow =
        TrackRow(fix.wallMs, LatLon(fix.lat, fix.lon), fix.accuracyM, fix.altitudeM, fix.speedMps, fix.provider)

    fun serviceState(wallMs: Long): ServiceStateSnapshot = ServiceStateSnapshot(
        state = ServiceRegState.OUT_OF_SERVICE,
        emergencyOnly = false,
        operatorNumeric = null,
        operatorAlphaLong = null,
        roaming = null,
        observedWallMs = wallMs,
        observedElapsedMs = elapsedAt(wallMs),
    )

    fun dataState(wallMs: Long): DataStateSnapshot =
        DataStateSnapshot(DataConnState.DISCONNECTED, networkType = 13, observedWallMs = wallMs, observedElapsedMs = elapsedAt(wallMs))

    fun displayInfo(wallMs: Long): DisplayInfoSnapshot =
        DisplayInfoSnapshot(networkType = 13, overrideNetworkType = 0, observedWallMs = wallMs, observedElapsedMs = elapsedAt(wallMs))

    fun pingRow(wallMs: Long): TrafficRow = TrafficRow(
        timeUtcMs = wallMs,
        test = TrafficTest.PING,
        target = "8.8.8.8",
        ok = false,
        seconds = 2.0,
        lossPct = 100.0,
        error = "no cellular network",
    )

    private fun elapsedAt(wallMs: Long): Long = START_ELAPSED_MS + (wallMs - START_WALL_MS)
}
