package com.openauto.dash

import android.content.Context
import android.content.res.Resources
import com.openauto.dash.link.CarNotice
import com.openauto.dash.link.CarNotices
import com.openauto.dash.link.LinkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * What the phone should tell the driver once out of the car: servicing
 * coming up or overdue (the inspection included), the engine's fault codes,
 * a battery found weak at rest, the particulate filter needing a long drive.
 * On the car these are said at start-up or on the tiles; on the phone they
 * become notifications ([CarNotice]), so a reminder is still there on the
 * day the garage can be called. Written in the launcher's language.
 */
internal object CarNews {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null

    private val _notices = MutableStateFlow<List<CarNotice>>(emptyList())
    val notices: StateFlow<List<CarNotice>> = _notices.asStateFlow()

    /** A weak battery at rest, kept for the day it was found: it is news until the phone has heard it. */
    private val battery = MutableStateFlow<CarNotice?>(null)

    /** Starts once for the process, after [Maintenance], [AiMechanic] and [CarCare] have their context; main thread. */
    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            combine(Maintenance.state, AiMechanic.state, CarCare.state, battery) { upkeep, mechanic, care, weak ->
                val res = AppLanguage.wrap(context.applicationContext).resources
                val now = System.currentTimeMillis()
                buildList {
                    addAll(upkeepNotices(res, upkeep.statuses(now), now))
                    faultNotice(res, mechanic.warningCodes, mechanic.diagnosis?.summary, now)?.let(::add)
                    filterNotice(res, care.filter.shortStreak, now)?.let(::add)
                    weak?.let(::add)
                }
            }.distinctUntilChanged { a, b -> a.map { it.id } == b.map { it.id } }.collect { _notices.value = it }
        }
    }

    /** [AiMechanic]'s live watch found the battery weak with the engine off. */
    fun weakBattery(volts: Double) {
        val context = appContext ?: return
        val res = AppLanguage.wrap(context).resources
        val now = System.currentTimeMillis()
        battery.value = CarNotice(
            id = "battery-${now / DAY_MS}",
            kind = CarNotice.BATTERY,
            title = res.getString(R.string.news_battery_title),
            text = res.getString(R.string.news_battery_text, String.format(Locale.getDefault(), "%.1f", volts)),
            at = now
        )
    }

    /** Runs for as long as the link is up (cancel it when the link ends): every notice now, and again at each change. */
    suspend fun report(send: (LinkMessage) -> Unit) {
        notices.collect { send(CarNotices(it)) }
    }

    /** Servicing SOON or DUE, one notice per item and stage: a new stage is new news. */
    private fun upkeepNotices(res: Resources, dues: List<UpkeepDue>, now: Long): List<CarNotice> =
        dues.filter { it.stage == UpkeepStage.SOON || it.stage == UpkeepStage.DUE }.map { d ->
            CarNotice(
                id = "upkeep-${d.kind.name}-${d.stage.name}",
                kind = CarNotice.UPKEEP,
                title = res.getString(if (d.stage == UpkeepStage.DUE) R.string.news_upkeep_due else R.string.news_upkeep_soon, res.getString(d.kind.labelRes)),
                text = UpkeepRules.line(d, Units.current.value).text(res),
                at = now
            )
        }

    /** The codes of the last scan, with the mechanic's summary when there is one. */
    private fun faultNotice(res: Resources, codes: List<String>?, summary: String?, now: Long): CarNotice? {
        if (codes.isNullOrEmpty()) return null
        return CarNotice(
            id = "fault-" + codes.sorted().joinToString("-"),
            kind = CarNotice.FAULT,
            title = res.getString(R.string.news_fault_title, codes.joinToString(", ")),
            text = summary?.takeIf { it.isNotBlank() } ?: res.getString(R.string.news_fault_text),
            at = now
        )
    }

    /** Short drives piling up on a filter car: said once the streak is long enough to matter, again every few more. */
    private fun filterNotice(res: Resources, streak: Int, now: Long): CarNotice? {
        if (!CarProfileStore.current.particleFilter || streak < FILTER_STREAK) return null
        return CarNotice(
            id = "filter-${streak / FILTER_STEP}",
            kind = CarNotice.FILTER,
            title = res.getString(R.string.news_filter_title),
            text = res.getString(R.string.news_filter_text, streak),
            at = now
        )
    }

    private const val DAY_MS = 24 * 3_600_000L
    private const val FILTER_STREAK = 5
    private const val FILTER_STEP = 5
}
