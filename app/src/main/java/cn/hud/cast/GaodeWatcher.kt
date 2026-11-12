package cn.hud.cast

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 高德状态观察者，两条相互独立的信号：
 *
 *  A. 前台判断（UsageStats）—— 高德手机版 / 车机版都适用，需要用户给"使用情况访问"权限。
 *  B. 导航生命周期广播 AUTONAVI_STANDARD_BROADCAST_SEND —— 只有高德车机版（amapauto /
 *     amapautk）会发，手机版没有。状态码沿用 hud-probe NavigationCycle 的定义：
 *        开始：8、10        结束：9、12、308(0x134)、39(0x27)
 *
 * 拿不到任何信号时返回 null，由上层退化成"手动开关 + 一直投"，绝不瞎猜。
 */
class GaodeWatcher(
    private val context: Context,
    private val onNavStateChanged: (active: Boolean) -> Unit,
    private val onNavInfo: ((roadName: String?, distanceText: String?, turnMask: Int?) -> Unit)? = null,
    /** 1.28：KEY_TYPE=13012 时的车道 JSON 兜底通道（车机版把车道数据放进 EXTRA_DRIVE_WAY） */
    private val onLaneJson: ((json: String) -> Unit)? = null,
    /** 1.42/1.47：红绿灯倒数广播（60073/60075，extras 为扁平字段） */
    private val onLightExtras: ((status: Int, red: Int, green: Int, dir: Int, waitRound: Int, srcType: Int, remain: Int, remainList: List<Int>) -> Unit)? = null,
    /** 1.53：限速广播泛化捕获（extras 含 speedLimit/limitSpeed 等键，值 km/h） */
    private val onSpeedLimit: ((value: Int, srcType: Int) -> Unit)? = null
) {

    companion object {
        const val ACTION_STANDARD_BROADCAST = "AUTONAVI_STANDARD_BROADCAST_SEND"

        /** 1.39：发往高德的请求广播（车道信息查询 KEY_TYPE=10062 走这个 action） */
        const val ACTION_STANDARD_BROADCAST_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
        const val EXTRA_KEY_TYPE = "KEY_TYPE"
        const val EXTRA_STATE = "EXTRA_STATE"

        val GAODE_PACKAGES = setOf(
            "com.autonavi.minimap",   // 高德地图（含 UCAR 车机渠道包）
            "com.autonavi.amapauto",  // 高德地图车机版
            "com.autonavi.amapautk"   // 高德地图车机版（共存包）
        )

        private const val TAG = "HudCast"

        fun isStart(state: Int): Boolean = state == 8 || state == 10

        fun isEnd(state: Int): Boolean = state == 9 || state == 12 || state == 0x134 || state == 0x27
    }

    @Volatile
    var navActive: Boolean = false
        private set

    @Volatile
    var lastNavStateCode: Int = Int.MIN_VALUE

    /**
     * 1.46：最近收到的广播 KEY_TYPE 滚动记录（主界面诊断行显示）。
     * 60073 红绿灯广播未命中时，从这里能看到这台车机实际都发了哪些类型，
     * 一次照片即可定位真实数据通道。
     */
    @Volatile
    var lastBcastTypes: String = ""
        private set

    private var receiver: BroadcastReceiver? = null

    // 1.39：车道信息主动查询轮询（KEY_TYPE=10062，官方「车道信息查询」）。
    // 部分版本高德只在变化时推送车道 JSON，主动轮询保证 HUD 车道指引持续有数据。
    private val lanePollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lanePollRunning = false
    private val lanePollTask = object : Runnable {
        override fun run() {
            if (!lanePollRunning || !navActive) {
                lanePollRunning = false
                return
            }
            try {
                val i = Intent(ACTION_STANDARD_BROADCAST_RECV).putExtra(EXTRA_KEY_TYPE, 10062)
                context.sendBroadcast(i)
            } catch (_: Throwable) {
            }
            lanePollHandler.postDelayed(this, 2000L)
        }
    }

    private fun startLanePoll() {
        if (lanePollRunning) return
        lanePollRunning = true
        lanePollHandler.postDelayed(lanePollTask, 500L)
    }

    /** 1.64：最近一条红绿灯类广播的完整 extras 键值串（诊断行显示）。
     *  实车确认巡航时 60075 广播照发但 st/r/g/dir/wr 全无效——
     *  真实键名只能从这里找，一张照片即可校准。 */
    var lastLightExtrasDump = ""
        private set

    /** 1.80：60073 到达计数（诊断行显示）。=0 说明引擎从未导出巡航红绿灯
     *  广播 → cruise.param.enable_traffic_light 门禁未放行（发送端问题）。 */
    @Volatile
    var light60073Count: Int = 0
        private set

    private fun stopLanePoll() {
        lanePollRunning = false
        lanePollHandler.removeCallbacks(lanePollTask)
    }

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != ACTION_STANDARD_BROADCAST) return
                val state = intent.getIntExtra(EXTRA_STATE, Int.MIN_VALUE)
                val type = intent.getIntExtra(EXTRA_KEY_TYPE, Int.MIN_VALUE)
                val extraKeys = intent.extras?.keySet()?.joinToString(",") ?: ""
                val extraVals = intent.extras?.keySet()?.joinToString(",") { k ->
                    val v = intent.extras?.get(k)
                    // 1.76：数组值展开显示——巡航分组倒计时疑似整型数组，
                    // 之前只显示 "[I" 类型名，一张照片什么都看不到
                    val s = when (v) {
                        is Int, is Long, is Boolean, is Float, is Double -> v.toString()
                        is String -> v
                        is IntArray -> v.joinToString(",", "[", "]")
                        is LongArray -> v.joinToString(",", "[", "]")
                        is ShortArray -> v.joinToString(",", "[", "]")
                        is Array<*> -> v.joinToString(",", "[", "]")
                        else -> v?.javaClass?.simpleName ?: "null"
                    }
                    "$k=$s"
                } ?: ""
                val senderPkg = try {
                    intent.`package` ?: intent.getStringExtra("packageName") ?: "（广播未标注来源）"
                } catch (_: Throwable) {
                    "（未知）"
                }
                Log.i(TAG, "收到导航广播 type=$type state=$state sender=$senderPkg")
                Log.i(TAG, "  extras 完整值: $extraVals")
                lastNavStateCode = state
                if (type != Int.MIN_VALUE) {
                    // 1.46：滚动记录最近 8 种广播类型（附 extras 键名摘要）
                    val summary = extraKeys.split(",").take(5).joinToString(",")
                    val entry = "$type[$summary]"
                    val parts = lastBcastTypes.split(" ▸ ").toMutableList()
                    if (parts.lastOrNull() != entry) {
                        parts.add(entry)
                        while (parts.size > 8) parts.removeAt(0)
                        lastBcastTypes = parts.joinToString(" ▸ ")
                    }
                }
                // 转向 / 道路名 / 剩余距离：车机版高德把 TBT 信息放在广播 extras 里。
                // 1.20：巡航模式的广播键名可能与导航不同，多备几组常见键名 best-effort 解析
                val road = pickText(
                    intent,
                    "EXTRA_NEXT_ROAD_NAME", "NEXT_ROAD_NAME",
                    "EXTRA_ROAD_NAME", "ROAD_NAME",
                    "EXTRA_CUR_ROAD_NAME", "CUR_ROAD_NAME",
                    "EXTRA_CURRENT_ROAD_NAME", "CURRENT_ROAD_NAME",
                    "EXTRA_MAIN_ROAD_NAME", "MAIN_ROAD_NAME"
                )
                // 1.21 距离语义修正：只取「到当前转向点」的剩余距离。
                // 之前 NEXT_SEG_REMAIN_DIS 优先，部分版本里它带的是累计值（当前段+下一段），
                // ROUTE_REMAIN_DIS 又是全程剩余——混进来数字就偏大。现在当前段优先，
                // NEXT_SEG 兜底，全程剩余不再采用。
                val dist = pickDistance(
                    intent,
                    "EXTRA_SEG_REMAIN_DIS", "SEG_REMAIN_DIS",
                    "EXTRA_CUR_SEG_REMAIN_DIS", "CUR_SEG_REMAIN_DIS",
                    "EXTRA_NEXT_SEG_REMAIN_DIS", "NEXT_SEG_REMAIN_DIS"
                )
                val turnMask = parseTurnMask(intent)
                if (road != null || dist != null || turnMask != null) {
                    onNavInfo?.invoke(road, dist, turnMask)
                }
                // 1.28：车道广播兜底——AIDL 断联时也能拿到车道指引
                if (type == 13012) {
                    val laneJson = intent.getStringExtra("EXTRA_DRIVE_WAY")
                    if (!laneJson.isNullOrBlank()) {
                        onLaneJson?.invoke(laneJson)
                    }
                }
                // 1.79 逆向定案（高德 9.5.13 dex 全量反编译）：
                //   · wh0.c（TrafficLightsCountdownInfoDisAction）→ KEY_TYPE=60073，
                //     extras = trafficLightStatus / dir / redLightCountDownSeconds /
                //     waitRound / greenLightLastSecond（ib.t() 版本开关，9.5.13 恒发）；
                //     数据源 = 引擎协议 80189（RspTrafficLightsCountdownInfoModel，单值，
                //     巡航时引擎只导出"当前灯"那一盏 → HUD 单胶囊显示即正确形态）；
                //   · yg.c（ControlTouchBeepAction）→ KEY_TYPE=60075 = 按键提示音响应，
                //     extras 只有 EXTRA_TOUCHBEEPRESULT——实车巡航诊断里那些
                //     st=-1/r=null 的 60075 空广播就是它，不是红绿灯；
                //   · 引擎侧巡航倒数有门禁（cruise.param.enable_traffic_light +
                //     CruiseTrafficLightAuth 云端授权），被禁时不导出 60073——
                //     这是巡航不显示的最可能根因，属高德侧开关，接收端无法绕过。
                // 触发条件：60073 恒解析；60075 仅在带红绿灯键时解析（华阳车机层
                // 可能在 60075 里夹带 EXTRA_TRAFFIC_LIGHT_* 键，保留兼容）。
                val hasLightKeys = intent.hasExtra("trafficLightStatus") ||
                    intent.hasExtra("redLightCountDownSeconds") || intent.hasExtra("greenLastSecond") ||
                    intent.hasExtra("greenLightLastSecond") || intent.hasExtra("waitRound") ||
                    intent.hasExtra("EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS") ||
                    intent.hasExtra("EXTRA_TRAFFIC_LIGHT_COUNTDOWNSECONDS")
                if (type == 60073 || hasLightKeys) {
                    // 1.80：60073 到达计数（区分门禁未放行与键名形态问题）
                    if (type == 60073) light60073Count++
                    // 1.69：诊断行完整 extras 透出——车机层（华阳）若换键名一张照片可定位
                    val st = pickIntExtra(intent, "trafficLightStatus", "EXTRA_TRAFFIC_LIGHT_STATUS")
                    val red = pickIntExtra(intent, "redLightCountDownSeconds")
                    val green = pickIntExtra(intent, "greenLastSecond", "greenLightLastSecond")
                    val dir = pickIntExtra(intent, "dir")
                    val wr = pickIntExtra(intent, "waitRound")
                    val remain = pickIntExtra(
                        intent,
                        "EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS", "EXTRA_TRAFFIC_LIGHT_COUNTDOWNSECONDS"
                    )
                    Log.i(TAG, "红绿灯广播(type=$type)：status=$st red=$red green=$green dir=$dir waitRound=$wr remain=$remain")
                    // 1.64：完整 extras 透出到诊断行（beep 广播已在上方被过滤，不会覆盖）
                    lastLightExtrasDump = "红绿灯广播$type 完整extras: ${extraVals.take(220)}"
                    // 1.76：数组形态 extras 全量提取（巡航分组倒计时）
                    var remainList = pickIntList(
                        intent,
                        "EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS", "EXTRA_TRAFFIC_LIGHT_COUNTDOWNSECONDS"
                    )
                    // 1.80：倒数泛化扫描——已知键全部缺失但有灯色时，扫描 extras
                    // 里任何含 countdown/remain/second 的数字键（1..300 秒合理域）。
                    // 华阳车机层换键名形态时自动抓到倒数；候选全部交给解析层
                    // 锁定仲裁挑本车灯组，诊断行会显示「列表=…」供校准。
                    if ((st > 0 || wr > 0) && remain < 0 && remainList.isEmpty()) {
                        val found = ArrayList<Pair<String, Int>>()
                        for (k in intent.extras?.keySet() ?: emptySet()) {
                            val lk = k.lowercase()
                            if (("count" in lk || "remain" in lk || "second" in lk) &&
                                "waitround" !in lk && "key_type" !in lk
                            ) {
                                val v = pickIntExtra(intent, k)
                                if (v in 1..300) found.add(k to v)
                            }
                        }
                        if (found.isNotEmpty()) {
                            Log.i(TAG, "红绿灯倒数泛化扫描命中(type=$type)：$found")
                            remainList = found.map { it.second }
                            lastLightExtrasDump = "倒数泛化命中$type: " +
                                found.joinToString(",") { "${it.first}=${it.second}" }
                        }
                    }
                    onLightExtras?.invoke(st, red, green, dir, wr, type, remain, remainList)
                }
                when {
                    isStart(state) -> {
                        navActive = true
                        startLanePoll()
                        onNavStateChanged(true)
                    }

                    isEnd(state) -> {
                        navActive = false
                        stopLanePoll()
                        onNavStateChanged(false)
                    }
                }

                // 1.53：限速广播泛化捕获——不限 KEY_TYPE，任何广播 extras 里
                // 带限速键（speedLimit / limitSpeed / roadLimitSpeed / maxSpeed
                // 等，km/h 标志值）都提取。命中时 logcat 打印来源类型供校准。
                val speedVal = pickIntExtra(
                    intent,
                    "speedLimit", "SPEED_LIMIT", "EXTRA_SPEED_LIMIT",
                    "limitSpeed", "LIMIT_SPEED", "roadLimitSpeed",
                    "currentSpeedLimit", "speedLimitValue", "maxSpeed", "MAX_SPEED"
                )
                if (speedVal in 5..200) {
                    Log.i(TAG, "限速广播(type=$type)：speedLimit=$speedVal")
                    onSpeedLimit?.invoke(speedVal, type)
                }
            }
        }
        try {
            val filter = IntentFilter(ACTION_STANDARD_BROADCAST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
            if (navActive) startLanePoll()
            Log.i(TAG, "高德导航广播监听已注册（AUTONAVI_STANDARD_BROADCAST_SEND），" +
                "正在等待高德车机版（amapauto / amapautk）发出广播…")
        } catch (t: Throwable) {
            Log.w(TAG, "注册导航广播失败：${t.message}")
        }
    }

    /**
     * 转向方向解析（1.17）：把广播里的转向图标名 / 类型码归一成方向掩码。
     * 掩码：0x1 直行 0x2 左转 0x4 右转 0x8 稍向左 0x20 稍向右 0x10 掉头。
     * 图标名来自各家实现五花八门，这里做关键词 best-effort + 数字编码兜底，
     * 都不认识就返回 null（HUD 不画箭头），同时日志里已打完整值便于后续补映射。
     */
    private fun parseTurnMask(intent: Intent): Int? {
        // NEW_ICON 是原始 GBL ManeuverIconID；ICON 是车机版 convertIcon/switchIcon
        // 转换后的值（仅 65/66 特殊，其余不变），语义相同
        val icon = pickText(
            intent,
            "NEW_ICON", "EXTRA_NEW_ICON",
            "EXTRA_ICON_NAME", "ICON_NAME",
            "ICON", "EXTRA_ICON",
            "EXTRA_TURN_ICON", "TURN_ICON"
        )
        if (!icon.isNullOrBlank()) {
            val n = icon.lowercase()
            val slight = n.contains("slight") || n.contains("little")
            val byWord = when {
                n.contains("uturn") || n.contains("u_turn") ||
                    n.contains("掉头") || n.contains("调头") -> 0x10
                n.contains("left") || n.contains("左") -> if (slight) 0x8 else 0x2
                n.contains("right") || n.contains("右") -> if (slight) 0x20 else 0x4
                n.contains("straight") || n.contains("直行") || n.contains("直") -> 0x1
                else -> 0
            }
            if (byWord != 0) return byWord

            // 1.31：数字编码按 GBL ManeuverIconID 官方表
            // （2 左 / 3 右 / 4 稍左 / 5 稍右 / 6-8 掉头 / 9 直行）
            val num = n.filter { it.isDigit() }.toIntOrNull()
            if (num != null) {
                return when (num) {
                    0x02 -> 0x2
                    0x03 -> 0x4
                    0x04 -> 0x8
                    0x05 -> 0x20
                    0x06, 0x07, 0x08, 0x13 -> 0x10
                    0x09, 0x0A, 0x0B, 0x0C, 0x14 -> 0x1
                    0x41 -> 0x8
                    0x42 -> 0x20
                    else -> null
                }
            }
        }
        return null
    }

    /** 1.42：读扁平 Int extras（兼容数字字符串形态，缺失返回 -1）；
     *  1.76：extras 为整型/字符串数组时取首个有效元素 */
    private fun pickIntExtra(intent: Intent, vararg keys: String): Int {
        for (key in keys) {
            val v = try {
                intent.extras?.get(key)
            } catch (_: Throwable) {
                null
            } ?: continue
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: continue
                is IntArray -> v.firstOrNull { it >= 0 }?.takeIf { it != Int.MIN_VALUE } ?: continue
                is LongArray -> v.firstOrNull { it >= 0 }?.toInt() ?: continue
                is Array<*> -> v.mapNotNull {
                    (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull()
                }.firstOrNull() ?: continue
                else -> continue
            }
            if (n != -1) return n
        }
        return -1
    }

    /** 1.76：读数组型 extras 的全部整数元素（巡航分组倒计时形态），空返回 emptyList */
    private fun pickIntList(intent: Intent, vararg keys: String): List<Int> {
        for (key in keys) {
            val v = try {
                intent.extras?.get(key)
            } catch (_: Throwable) {
                null
            } ?: continue
            val list: List<Int> = when (v) {
                is IntArray -> v.toList()
                is LongArray -> v.map { it.toInt() }
                is ShortArray -> v.map { it.toInt() }
                is Array<*> -> v.mapNotNull {
                    (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull()
                }
                is Number -> listOf(v.toInt())
                is String -> listOfNotNull(v.toIntOrNull())
                else -> continue
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun pickText(intent: Intent, vararg keys: String): String? {
        for (k in keys) {
            val v = try {
                intent.getStringExtra(k)
            } catch (_: Throwable) {
                null
            }
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return null
    }

    private fun pickDistance(intent: Intent, vararg keys: String): String? {
        for (k in keys) {
            val raw: Any? = try {
                intent.extras?.get(k)
            } catch (_: Throwable) {
                null
            }
            val meters = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
                else -> null
            } ?: continue
            if (meters < 0) continue
            return if (meters >= 1000) {
                val km = meters / 1000.0
                if (km >= 10) "${km.toInt()}km" else "%.1fkm".format(km)
            } else {
                "${meters}m"
            }
        }
        return null
    }

    fun stop() {
        stopLanePoll()
        val r = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
    }

    /** true/false 为确定结果，null 表示没有权限或查不到。 */
    fun isGaodeForeground(): Boolean? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events: UsageEvents = try {
            usm.queryEvents(now - 15_000L, now)
        } catch (t: Throwable) {
            return null
        }
        val event = UsageEvents.Event()
        var lastForegroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val isForeground = when (type) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> true
                UsageEvents.Event.MOVE_TO_BACKGROUND -> false
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    when (type) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> true
                        UsageEvents.Event.ACTIVITY_PAUSED -> false
                        else -> null
                    }
                } else {
                    null
                }
            }
            if (isForeground == null) continue
            if (isForeground == true) {
                lastForegroundPackage = event.packageName
            } else if (event.packageName == lastForegroundPackage) {
                lastForegroundPackage = null
            }
        }
        val pkg = lastForegroundPackage ?: return false
        return pkg in GAODE_PACKAGES
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
