package cn.hud.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import org.json.JSONObject

/** 单条车道。directions 为方向位掩码：1 直行 / 2 左转 / 4 右转 / 8 斜向 / 0x10 掉头 */
data class HudLane(
    val directions: Int,
    val advised: Boolean,
    val number: Int,
    val icon: Int
)

/** HUD 卡片状态：车道 + 推荐车道 + 红绿灯倒数 + 道路名/距离，全部可缺省。 */
data class HudCardState(
    val lanes: List<HudLane> = emptyList(),
    val laneEnabled: Boolean = false,
    /** 车道数据的更新时间戳：超过时限视为过期，HUD 不再画旧车道（1.17 修复过期残留） */
    val lanesAtMs: Long = 0L,
    /**
     * 1.36：最近一次「收到车道消息」的时间戳（不管内容有没有变）。
     * 高德活跃推送时每秒都会发车道消息；超过 15 秒一条都没有 = 高德已经
     * 不再显示车道指引，HUD 也要跟着隐藏（之前常驻不消失导致与高德不一致）。
     */
    val laneMsgAtMs: Long = 0L,
    val lightStatus: Int = -1,
    val lightSeconds: Int = -1,
    /** 红绿灯倒数更新时间戳，过期同样隐藏 */
    val lightAtMs: Long = 0L,
    /**
     * 1.36：红绿灯数据里自带的方向箭头（部分版本 trafficLights[0] 带
     * lightIcon / turnIcon 等字段）。0=协议没给，绘制层回落到距离启发式。
     */
    val lightTurnMask: Int = 0,
    /** 转向箭头方向掩码（1 直 / 2 左 / 4 右 / 8 稍左 / 0x20 稍右 / 0x10 掉头），0=无 */
    val turnMask: Int = 0,
    val turnAtMs: Long = 0L,
    /**
     * 1.36：当前段剩余距离（米，30407 segRemainDis）。红绿灯圆内箭头用：
     * 转向点距自车 ≤300m 时转向才发生在本路口，圆内才画转向箭头，
     * 否则高德画的是直行——之前无条件下转向箭头导致圆内箭头与高德不一致。
     */
    val segRemainDisM: Int = 0,
    val connected: Boolean = false,
    val lastUpdateMs: Long = 0L,
    /** true=导航中 false=巡航中（车机版高德两种状态都往同一条通路发数据） */
    val navActive: Boolean = false,
    val roadName: String? = null,
    val distanceText: String? = null,
    /** 1.26：全程剩余里程（米）与剩余时长（秒），来自 30407 routeRemainDis / routeRemainTime */
    val routeRemainDisM: Int = 0,
    val routeRemainTimeS: Int = 0,
    /**
     * 1.44：引导数据（当前段距离 / 转向 / 道路 / 全程信息）最后一次
     * 「实际变化」的时间戳。导航结束后这版车机不发结束广播也不发清空
     * 消息，最后一条 30407 会永久冻结——服务层靠它识别冻结残留并清除。
     */
    val guideChangedAtMs: Long = 0L,
    /**
     * 1.53：当前道路限速（km/h，按限速标志显示值），0=无数据。
     * 双通道获取：广播 extras 泛化捕获（speedLimit/limitSpeed 等键）+
     * AIDL JSON 深扫（键名含 speedLimit/limitSpeed/maxSpeed 的数值）。
     * 广播只在限速变化时推送，故不做超时清除，取最后已知值。
     */
    val speedLimit: Int = 0,
    val speedLimitAtMs: Long = 0L,
    /**
     * 1.60：巡航红绿灯按行驶方向分行的倒计时（直行一行、左转一行）。
     * 来源：60075 广播 / AIDL 消息里的 dir 字段——同一路口不同行驶方向
     * 各自的状态与倒数分开记录，绘制层巡航最多画两行。
     */
    val cruiseLights: List<CruiseLight> = emptyList()
)

/**
 * 1.60：单个行驶方向的红绿灯行（巡航两行显示用）。
 * @param dirRaw 60075 广播原始 dir 值（4=直行 1=左转 0=掉头 2/3=右转…）
 */
data class CruiseLight(
    val dirRaw: Int,
    val status: Int,
    val seconds: Int,
    val dirMask: Int,
    val atMs: Long
)

/**
 * 车机版高德（com.autonavi.amapautk / com.autonavi.amapauto）官方 JSON 数据服务客户端。
 *
 * 协议（来自 hud-probe 反编译 + 车机版 dex 验证）：
 *   1. bindService(ComponentName(pkg, "com.autonavi.amapauto.protocol.service.JsonProtocolService"))
 *   2. 接口描述符必须是 "com.autonavi.amapauto.aidl.IJsonProtocolInterface"
 *   3. transact(2) 注册回调：writeInterfaceToken + writeString(自己包名) + writeStrongBinder(回调)
 *   4. 回调描述符 "com.autonavi.amapauto.aidl.IJsonProtocolReceiveInterface"，
 *      transact 1/2 各带一个 JSON 字符串
 *   5. JSON 外层 { protocolId, messageType:"dispatch", data:{...} }；
 *      车道消息 protocolId=30424，data 内含 trafficLaneInfos[] / trafficLaneAdvised / drive_way_*
 */
class AmapProtocol(
    private val context: Context,
    private val onState: (HudCardState) -> Unit
) {

    companion object {
        private const val TAG = "HudCast"
        private const val SERVICE_CLASS = "com.autonavi.amapauto.protocol.service.JsonProtocolService"
        private const val SERVICE_ACTION = "com.autonavi.amapauto.aidl.json_protocol_service"
        private const val IFACE = "com.autonavi.amapauto.aidl.IJsonProtocolInterface"
        private const val CB_IFACE = "com.autonavi.amapauto.aidl.IJsonProtocolReceiveInterface"
        private const val TRANSACT_REGISTER = 2
        private const val LANE_PROTOCOL_ID = 30424
        /** 1.22：导航引导信息（转向图标 / 道路名 / 剩余距离）协议号，字段来自 GuideInfoModel 反汇编 */
        private const val GUIDE_PROTOCOL_ID = 30407

        /** 1.38 相位交替推断：红绿灯消息间隔超过此时长视为断流，历史灯色作废 */
        private const val LIGHT_PHASE_GAP_MS = 10_000L

        /** 车机版包名，按优先级尝试 */
        val CANDIDATES = listOf("com.autonavi.amapautk", "com.autonavi.amapauto")
    }

    private var bound = false
    private var boundPkg: String? = null
    private var remote: IBinder? = null
    private var state = HudCardState()

    /** 1.60：按行驶方向分行的红绿灯状态（巡航两行显示），dir 原值 → 行 */
    private val lightRows = LinkedHashMap<Int, CruiseLight>()

    /** 行排序：直行(4)在上一行、左转(1)在下一行，其余方向排在后面 */
    private fun cruiseLightSnapshot(): List<CruiseLight> {
        val now = System.currentTimeMillis()
        return lightRows.values
            .filter { it.status > 0 && now - it.atMs < 60_000L }
            .sortedBy { if (it.dirRaw == 4) 0 else if (it.dirRaw == 1) 1 else 2 + it.dirRaw }
    }
    private val seenProtocolIds = HashSet<Int>()

    /** 1.53：限速字段的来源协议号（首次深扫命中后，只对该协议号继续扫描） */
    private var speedProtocolId: Int? = null

    /**
     * 1.62：巡航红绿灯 AIDL 泛化深扫的来源协议号。
     * 实车反馈：巡航（非导航）时 60075 广播不推，HUD 红绿灯空白；
     * 而车机版巡航画面自身有红绿灯倒计时 → 数据走了 AIDL 但 messageType
     * 不含 TrafficLight（巡航信息类协议）。对策与限速深扫同款：
     * 任意消息全树扫红绿灯特异键（status/red/green），首次命中锁定协议号，
     * 之后只扫该协议，避免每条消息全树遍历。
     */
    private var lightScanProtocolId: Int? = null

    // 1.38 红绿灯相位交替推断：上一条倒数值 / 时间 / 当前判定灯色（1红 2绿）
    private var prevLightSec = -1
    // 1.76：仅有通用倒数（巡航 60075 的 remain，无 status/红绿倒数键）时的
    // 相位推断状态——1 红 2 绿（HUD 色约定）、上次倒数、上次时间
    private var remainPhaseGuess = 0
    private var prevRemainOnlySec = -1
    private var prevRemainOnlyAtMs = 0L
    // 1.78：多方向倒数锁定——高德巡航界面同屏显示 2-3 个倒数（不同方向 /
    // 灯组），extras 列表取错位会让 HUD 数字跳变或显示别家灯组的倒数。
    // 按「每秒递减」的连续性特征锁定本车当前灯组：上一锁定值 -1（或同值
    // 重复推送）者延续锁定；锁定断链（相位切换数值跳变）时按同索引位跟随
    // （同一灯组在列表中位置不变），超过 20 秒没锁到（过路口、灯组换页）
    // 重新从首值起锁。
    private var lockListIdx = -1
    private var lockListSec = -1
    private var lockListAtMs = 0L
    private var prevLightAtMs = 0L
    private var altLightColor = -1

    // 1.49：最近一次广播 13012 车道数据时间（广播数据优先于 AIDL 退化值）
    private var lastBcastLaneAtMs = 0L

    // 1.47：上一条消息的红/绿倒数（判断「哪个键在递减」= 当前相位）
    private var prevRedSec = 0
    private var prevGreenSec = 0

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "车机版高德数据服务进程已退出")
        remote = null
        state = state.copy(connected = false)
        publish()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val desc = try {
                service.interfaceDescriptor
            } catch (t: Throwable) {
                null
            }
            if (desc != IFACE) {
                Log.w(TAG, "车机版高德数据服务接口不匹配（实际=$desc），放弃")
                StatusBus.publish(Status.failed("车机版高德数据接口不匹配：$desc"))
                unbindQuietly()
                return
            }
            remote = service
            try {
                service.linkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
            val ok = try {
                registerCallback(service)
            } catch (t: Throwable) {
                Log.w(TAG, "注册数据回调失败：${t.message}")
                false
            }
            state = state.copy(connected = ok)
            publish()
            if (ok) {
                Log.i(TAG, "✅ 已连通车机版高德数据服务（$boundPkg），等待导航 JSON…")
                StatusBus.publish(Status.casting("已连通车机版高德数据服务，HUD 卡片常驻等待导航数据…"))
            } else {
                StatusBus.publish(Status.failed("车机版高德数据服务已连上，但注册回调被拒绝"))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "车机版高德数据服务断开：$name")
            // 1.16：必须把 bound 复位并解绑，否则看门狗无法重新建立绑定，
            // 高德重启一次 HUD 就永久没数据了
            remote = null
            unbindQuietly()
            boundPkg = null
            state = state.copy(connected = false)
            publish()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "车机版高德数据服务绑定失效：$name")
            remote = null
            unbindQuietly()
            boundPkg = null
            state = state.copy(connected = false)
            publish()
        }
    }

    /** 当前是否已连上并注册成功（看门狗用）。 */
    val isConnected: Boolean get() = state.connected

    /**
     * 1.22：是否收到过 30407 引导信息（转向/道路/距离）。
     * 为 true 时这三种字段以 AIDL 数据为准；否则保持广播路径的值不被覆盖。
     */
    var hasGuideData: Boolean = false
        private set

    /** 回调 Binder：高德服务通过它把 JSON 推回来 */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1, 2 -> {
                    data.enforceInterface(CB_IFACE)
                    val json = try {
                        data.readString()
                    } catch (_: Throwable) {
                        null
                    }
                    try {
                        reply?.writeNoException()
                        if (code == 2) reply?.writeString(null)
                    } catch (_: Throwable) {
                    }
                    if (!json.isNullOrEmpty()) {
                        handleJson(json)
                    }
                    return true
                }

                INTERFACE_TRANSACTION -> {
                    try {
                        reply?.writeString(CB_IFACE)
                    } catch (_: Throwable) {
                    }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    fun start(): Boolean {
        if (bound) return true
        val pm = context.packageManager
        for (pkg in CANDIDATES) {
            val cn = ComponentName(pkg, SERVICE_CLASS)
            val exists = try {
                pm.getServiceInfo(cn, 0)
                true
            } catch (_: Throwable) {
                false
            }
            if (!exists) continue

            val intent = Intent(SERVICE_ACTION).setComponent(cn)
            val ok = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.w(TAG, "绑定车机版高德（$pkg）失败：${t.message}")
                false
            }
            if (ok) {
                bound = true
                boundPkg = pkg
                Log.i(TAG, "正在绑定车机版高德数据服务（$pkg）…")
                StatusBus.publish(Status.casting("正在连接车机版高德数据服务（$pkg）…"))
                return true
            }
        }
        Log.w(TAG, "未找到车机版高德数据服务（amapautk / amapauto 都不存在）")
        StatusBus.publish(
            Status.failed("没有找到车机版高德（com.autonavi.amapautk）。请先安装车机版高德，再进此模式")
        )
        return false
    }

    fun stop() {
        val r = remote
        remote = null
        if (r != null) {
            try {
                r.unlinkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
        }
        unbindQuietly()
        state = HudCardState()
    }

    private fun unbindQuietly() {
        if (!bound) return
        bound = false
        try {
            context.unbindService(connection)
        } catch (_: Throwable) {
        }
    }

    private fun registerCallback(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            data.writeString(context.packageName)
            data.writeStrongBinder(callback)
            val ok = service.transact(TRANSACT_REGISTER, data, reply, 0)
            if (ok) reply.readException()
            ok
        } catch (e: RemoteException) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ---------- JSON 解析 ----------

    /** 1.28：广播车道 JSON 兜底入口（KEY_TYPE=13012 / EXTRA_DRIVE_WAY），与 AIDL 共用解析。 */
    fun feedLaneJson(json: String) {
        handleJson(json)
    }

    /**
     * 1.42：红绿灯倒数广播入口（KEY_TYPE=60073 扁平 extras）。
     * 用户诊断行实证 AIDL JSON 通道没有红绿灯数据（红倒数/绿倒数全 null），
     * 车机版真实数据源是这条广播（反编译 wh0.c 组装代码证实字段名）。
     * 复用 parseTrafficLight 的判定/去抖/诊断逻辑。
     */
    fun feedLightBroadcast(
        status: Int, red: Int, green: Int, dir: Int, waitRound: Int = -1,
        srcType: Int = 0, remain: Int = -1, remainList: List<Int> = emptyList()
    ) {
        // 1.64：全空 extras（st/r/g/dir/wr 全 -1）直接忽略——实车确认巡航时
        // 60075 广播照发但 extras 无有效红绿灯键，之前每次都进解析并被当成
        // 「无红绿灯」触发清空判定，把已有的分行数据冲掉。
        if (status < 0 && red < 0 && green < 0 && dir < 0 && waitRound < 0 &&
            remain < 0 && remainList.isEmpty()
        ) return
        // 1.79：waitRound（等待轮数）来自发送端 wh0.c 确认语义——红灯等待时
        // >0。状态键缺失（华阳车机层换键形态）时用它兜底判红。
        val effStatus = if (status < 1 && waitRound > 0) 1 else status
        try {
            val d = JSONObject()
            d.put("trafficLightStatus", effStatus)
            if (red >= 0) d.put("redLightCountDownSeconds", red)
            if (green >= 0) d.put("greenLastSecond", green)
            if (dir >= 0) d.put("dir", dir)
            if (waitRound >= 0) d.put("waitRound", waitRound)
            // 1.69：诊断行完整 extras 透出实车真实键名——
            // EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS=30（当前相位剩余秒）与
            // EXTRA_TRAFFIC_LIGHT_COUNTDOWNSECONDS=30。作为通用倒数接入。
            // 1.76：巡航显示多方向倒数（如 左转41/直行36），extras 若为
            // 整型数组，全列表交给解析层按连续性锁定本车当前灯组（1.78）。
            if (remain >= 0) d.put("countDown", remain)
            if (remainList.isNotEmpty()) d.put("countDownList", remainList.joinToString(","))
            if (parseTrafficLight(d, "Broadcast$srcType")) publish()
        } catch (_: Throwable) {
        }
    }

    private fun handleJson(json: String) {        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return
        }
        val protocolId = root.optInt("protocolId", -1)
        val messageType = root.optString("messageType", "")
        val payload = root.optJSONObject("data") ?: root

        // 1.46：红绿灯消息诊断改为「原始 JSON 字符串直出」——1.43 的键值 dump
        // 在字段名/嵌套形态未知时仍可能漏显，原始字符串不受解析影响，
        // 一张照片即可看到这条消息里到底有什么
        val lightRaw = if (messageType.contains("TrafficLight", true)) {
            json.replace("\n", "").take(260)
        } else ""

        var changed = false
        if (parseGuideInfo(payload, protocolId)) changed = true
        if (parseLanes(payload)) changed = true

        // 1.53：AIDL 消息限速深扫——首次全协议扫，命中后锁定来源协议号，
        // 之后只扫该协议（轻量递归，避免每条消息全树遍历）
        if (speedProtocolId == null || speedProtocolId == protocolId) {
            val sf = HashMap<String, Int>()
            deepScanSpeed(root, sf, 0)
            if (sf.isNotEmpty()) {
                speedProtocolId = protocolId
                val v = sf.values.first()
                if (feedSpeedLimit(v, "AIDL${protocolId}深扫")) changed = true
            }
        }

        // 1.46：红绿灯消息处理重构。空消息（data{}）之前会触发「清空红绿灯」并
        // 返回 changed=true，把真实数据通道也挡在后面；现在：
        // - 消息里有直接键 → 正常解析；
        // - 没有直接键 → 深度递归扫描整棵 JSON（含字符串内嵌 JSON、数组子对象）；
        // - 深扫也没命中 → 完全跳过（不清空、不覆盖诊断行）
        val lightMsg = messageType.contains("TrafficLight", true)
        val hasDirectLightKey = payload.has("trafficLightStatus") ||
            payload.has("redLightCountDownSeconds") ||
            payload.has("greenLightLastSecond") ||
            payload.has("countDown") ||
            payload.has("trafficLights")
        if (!lightMsg || hasDirectLightKey) {
            if (parseTrafficLight(payload, messageType, lightRaw)) changed = true
        } else {
            val found = HashMap<String, Int>()
            deepScanLight(root, found, 0)
            if (found.isNotEmpty()) {
                try {
                    val synth = JSONObject()
                    found["status"]?.let { synth.put("trafficLightStatus", it) }
                    found["red"]?.let { synth.put("redLightCountDownSeconds", it) }
                    found["green"]?.let { synth.put("greenLightLastSecond", it) }
                    found["gen"]?.let { synth.put("countDown", it) }
                    found["dir"]?.let { synth.put("dir", it) }
                    Log.i(TAG, "红绿灯深度扫描命中：$found")
                    if (parseTrafficLight(synth, messageType, "$lightRaw ◆深扫=$found")) changed = true
                } catch (_: Throwable) {
                }
            } else {
                Log.i(TAG, "红绿灯消息无数据（跳过，不清空）：type=$messageType 原始=${json.take(120)}")
            }
        }

        // 1.62：巡航红绿灯 AIDL 泛化深扫。巡航模式下高德不一定发 60075 广播，
        // 红绿灯数据可能混在巡航信息协议里（messageType 不含 TrafficLight），
        // 顶层键检查（parseTrafficLight 的 byKey 门）看不到嵌套字段。
        // 对所有「非红绿灯类型」消息全树扫特异键；命中 status/red/green 才算
        // 红绿灯消息（dir/countDown 单独出现不触发，太泛），首次命中锁定
        // 来源协议号，后续只扫该协议。
        if (!lightMsg && !hasDirectLightKey &&
            (lightScanProtocolId == null || lightScanProtocolId == protocolId)
        ) {
            val lf = HashMap<String, Int>()
            deepScanLight(root, lf, 0)
            if (lf.containsKey("status") || lf.containsKey("red") || lf.containsKey("green")) {
                lightScanProtocolId = protocolId
                try {
                    val synth = JSONObject()
                    lf["status"]?.let { synth.put("trafficLightStatus", it) }
                    lf["red"]?.let { synth.put("redLightCountDownSeconds", it) }
                    lf["green"]?.let { synth.put("greenLightLastSecond", it) }
                    lf["gen"]?.let { synth.put("countDown", it) }
                    lf["dir"]?.let { synth.put("dir", it) }
                    Log.i(TAG, "巡航红绿灯深扫命中：protocolId=$protocolId $lf")
                    if (parseTrafficLight(synth, "AIDL${protocolId}巡航", "◆深扫=$lf")) changed = true
                } catch (_: Throwable) {
                }
            }
        }

        if (changed) {
            state = state.copy(lastUpdateMs = System.currentTimeMillis(), connected = true)
            publish()
        } else if (seenProtocolIds.add(protocolId)) {
            // 未知协议先记录一次，便于后续扩展
            val keys = payload.keys().asSequence().take(12).joinToString(",")
            Log.i(TAG, "收到未解析协议 protocolId=$protocolId type=$messageType keys=[$keys]")
        }
    }

    /**
     * 1.22：导航引导信息（protocolId=30407，GuideInfoModel）。
     * 「当前路口指示」的数据主通路：icon=转向图标编号，nextRoadName/curRoadName=道路名，
     * segRemainDis=到当前转向点剩余距离。字段名取自车机版 dex 反汇编 JSON 序列化键。
     */
    private fun parseGuideInfo(d: JSONObject, protocolId: Int): Boolean {
        if (protocolId != GUIDE_PROTOCOL_ID) return false
        var changed = false
        hasGuideData = true

        // ---- 转向图标 ----
        // 高德 icon 编码：1 左转 / 2 右转 / 3 直行 / 4 掉头 / 5 左前方(稍左) / 6 右前方(稍右) /
        // 7 左后方 / 8 右后方；另有 1001..1010 老图标编码兜底。icon=0 表示无转向，清空。
        val icon = d.opt("icon")
        if (icon != null) {
            val iconVal = when (icon) {
                is Number -> icon.toInt()
                is String -> icon.toIntOrNull() ?: -1
                else -> -1
            }
            if (iconVal >= 0) {
                val mask = turnMaskFromIcon(iconVal)
                turnDbg = "转向icon=$iconVal(${describeIcon(iconVal)}) 距离=${state.segRemainDisM}m"
                if (iconVal == 0 || mask == null) {
                    if (state.turnMask != 0) {
                        state = state.copy(turnMask = 0, turnAtMs = System.currentTimeMillis())
                        changed = true
                    }
                } else if (mask != state.turnMask) {
                    state = state.copy(turnMask = mask, turnAtMs = System.currentTimeMillis())
                    Log.i(TAG, "转向更新：icon=$iconVal -> mask=0x${Integer.toHexString(mask)}")
                    changed = true
                }
            }
        }

        // ---- 道路名：优先「即将驶入的道路」，巡航时用当前道路 ----
        val road = pickStr(d, "nextRoadName", "curRoadName")
        if (road != null && road != state.roadName) {
            state = state.copy(roadName = road)
            changed = true
        }

        // ---- 剩余距离：只取当前段（segRemainDis），不碰 nextSegRemainDis / routeRemainDis ----
        val segDis = pickOptInt(d, "segRemainDis", "segRemainDisAuto")
        if (segDis != null && segDis > 0) {
            if (segDis != state.segRemainDisM) {
                state = state.copy(segRemainDisM = segDis)
                changed = true
            }
            val text = if (segDis >= 1000) {
                val km = segDis / 1000.0
                if (km >= 10) "${km.toInt()}km" else "%.1fkm".format(km)
            } else {
                "${segDis}m"
            }
            if (text != state.distanceText) {
                state = state.copy(distanceText = text)
                changed = true
            }
        }
        // ---- 全程信息：剩余里程 / 剩余时长（预计到达时间由此计算） ----
        val remainDis = pickOptInt(d, "routeRemainDis", "routeAllDis")
        if (remainDis != null && remainDis > 0 && remainDis != state.routeRemainDisM) {
            state = state.copy(routeRemainDisM = remainDis)
            changed = true
        }
        val remainTime = pickOptInt(d, "routeRemainTime", "routeAllTime")
        if (remainTime != null && remainTime > 0 && remainTime != state.routeRemainTimeS) {
            state = state.copy(routeRemainTimeS = remainTime)
            changed = true
        }
        return changed
    }

    /**
     * 转向 icon 编码 → 方向掩码（1.26 按高德导航 SDK 官方 icon 对照表修正）：
     * 1 左转 / 2 右转 / 3 左前方 / 4 右前方 / 5 左后方 / 6 右后方 /
     * 7 掉头 / 8 直行 / 9-12 环岛系 / 13 靠左 / 14 靠右。
     * 之前 3-8 全错（直行 icon=8 被画成右转，就是「路口箭头画反」的原因）。
     */
    /**
     * 1.31：转向 icon → HUD 掩码，按高德 GBL SDK 官方 ManeuverIconID 全表
     * （a.amap.com/amapauto GBL 500 文档）逐项校准。此前 1.26 的表把
     * 1/2/8 系全部映射错（8 才是直行、3 才是右转），导致 HUD 转向与
     * 高德地图不一致——右转被画成稍左等。
     */
    private fun turnMaskFromIcon(icon: Int): Int? = when (icon) {
        0x02 -> 0x2       // TurnLeft 左转
        0x03 -> 0x4       // TurnRight 右转
        0x04 -> 0x8       // SlightLeft 左前方
        0x05 -> 0x20      // SlightRight 右前方
        0x06 -> 0x10      // TurnHardLeft 左后方（掉头式）
        0x07 -> 0x10      // TurnHardRight 右后方（掉头式）
        0x08 -> 0x10      // UTurn 掉头
        0x13 -> 0x10      // UTurnRight 右转掉头
        0x09 -> 0x1       // Continue 直行
        0x14 -> 0x1       // SpecialContinue 顺行
        0x0A -> 0x1       // Way 到达途经点
        0x0B, 0x11, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c -> 0x1  // 进环岛系
        0x0C, 0x12 -> 0x1 // 出环岛系
        0x0D, 0x0E, 0x0F, 0x10, 0x40 -> 0x1  // 服务区/收费站/目的地/隧道/充电站
        0x35, 0x36 -> 0x1 // 非导航段通过路口
        0x41 -> 0x8       // switchIcon 实测补丁：65→稍左
        0x42 -> 0x20      // switchIcon 实测补丁：66→稍右
        0x00, 0x01 -> null   // Null / 自车图标：不动当前箭头
        else -> null      // 未认识：保持原值，不误清
    }

    /**
     * 1.49：红绿灯广播 dir 字段（路口行驶方向）→ HUD 掩码。
     * 编码表来自 AMap Companion TrafficLightParser/AmapConstants（实测校准）：
     * 0=掉头 1=左转 2/3=右转 4=直行 5/6=左前斜 7/8=右前斜。
     * 导航模式下掉头在灯胶囊里显示直行（Companion 同款归一化）。
     */
    private fun lightDirToMask(dir: Int): Int = when (dir) {
        0 -> 0x1
        1 -> 0x2
        2, 3 -> 0x4
        4 -> 0x1
        5, 6 -> 0x8
        7, 8 -> 0x20
        else -> 0
    }

    /**
     * 1.39：车道方向 → HUD 掩码，按高德导航 SDK 官方 LaneAction 枚举全表
     * （a.amap.com Android_Navi_Doc constant-values）逐项校准：
     * 0 直 / 1 左 / 2 直+左 / 3 右 / 4 直+右 / 5 左掉头 / 6 左+右 / 7 直+左+右 /
     * 8 右掉头 / 9 直+左掉头 / 10 直+右掉头 / 11 左+左掉头 / 12 右+右掉头 /
     * 13 直行+扩展 / 14 左+左掉头+扩展 / 15 保留 / 16 直+左+左掉头 / 17 右+左掉头 /
     * 18 左+右+左掉头 / 19 直+右+左掉头 / 20 左+右掉头 / 21-25 公交/空/可变/专用/潮汐 /
     * 255 无对应车道。
     * 未知值返回 -1（调用方做位掩码兜底），不再默认直行。
     */
    private fun laneTypeToMask(v: Int): Int = when (v) {
        // 1.45：0 不再当作「直行」。实测这版 trafficLaneType=0 的语义是
        // 「无方向信息（普通车道）」，方向在 laneBackIcon 字段里；
        // 之前 0→直行 让 icon 分支永远走不到，所有车道都画成直行。
        0 -> 0
        1 -> 0x2
        2 -> 0x2 or 0x1
        3 -> 0x4
        4 -> 0x4 or 0x1
        5 -> 0x10
        6 -> 0x2 or 0x4
        7 -> 0x1 or 0x2 or 0x4
        8 -> 0x10
        9 -> 0x1 or 0x10
        10 -> 0x1 or 0x10
        11 -> 0x2 or 0x10
        12 -> 0x4 or 0x10
        13 -> 0x1
        14 -> 0x2 or 0x10
        16 -> 0x1 or 0x2 or 0x10
        17 -> 0x4 or 0x10
        18 -> 0x2 or 0x4 or 0x10
        19 -> 0x1 or 0x4 or 0x10
        20 -> 0x2 or 0x10
        21, 22, 23, 24, 25 -> 0x1
        else -> -1
    }

    /**
     * 1.50：drive_way_lane_Back_icon 车道箭头位图索引（lane_pdf_0..48）专用映射表，
     * 逐格对照 lane_pdf_contact.png 实拍索引图校准（掩码：0x1直 0x2左 0x4右 0x10掉头）。
     * 关键事实：位图 0-14 为蓝色（推荐车道形态）、15-48 为同构白色/扩展形态，
     * 与 LaneAction 枚举只有 0-14 数值巧合一致；15 起语义完全不同
     * （位图 16=左转，枚举 16=直+左+掉头），之前 Back_icon 走枚举表导致
     * 索引 ≥15 的车道方向全错。逐格内容：
     * 0直 1左 2直左 3右 4直右 5掉头 6左右 7直左右 8掉头 9掉头直 10直掉头
     * 11掉头左 12掉头右 13可变直 14可变掉头左 ｜ 15-29 与 0-14 同构（白）
     * 30直左 31掉头左 32直右 33直右 34-35左右 36-38直左右 39-42掉头直
     * 43-44掉头左 45-46掉头右 47可变掉头左 48掉头左
     *
     * 1.51 实拍修正：这台车机 13012 广播 b=31 的车道，高德车道条实际显示
     * 「掉头+左转」（同帧 b=0=直行、b=4=直+右 均与图一致）——说明实车
     * 图标表 30+ 段与手头对照图存在版本差异，31 不是图上的「直+左」。
     * 1.66 实拍修正：b=28 同为「掉头+左转」（推荐车道蓝色左+掉头同框
     * 确认），对照图的「可变直」不成立；13 暂无反证保留。
     * 以实车画面为准修正 31；其余双色段值遇到不符时按诊断行 b= 原始值
     * 继续逐个实拍校准。
     */
    private fun lanePdfToMask(v: Int): Int = when (v) {
        0, 15 -> 0x1
        1, 16 -> 0x2
        2, 17, 30 -> 0x1 or 0x2
        3, 18 -> 0x4
        4, 19, 32, 33 -> 0x1 or 0x4
        5, 8, 20, 23 -> 0x10
        6, 21, 34, 35 -> 0x2 or 0x4
        7, 22, 36, 37, 38 -> 0x1 or 0x2 or 0x4
        9, 10, 24, 25, 39, 40, 41, 42 -> 0x1 or 0x10
        // 1.66/1.67 实拍修正：b=28 高德车道条实际显示「直行+左转+掉头」
        // 三箭头（白色直行主体 + 深蓝左转 + 深蓝掉头，同框诊断行
        // Back_icon=28 确认）——与 1.51 的 b=31 同类版本差异，对照图上
        // 28 是「可变直」，实车不是。13 无实拍反证，暂保留「可变直」。
        28 -> 0x1 or 0x2 or 0x10
        11, 26, 31, 43, 44, 48 -> 0x2 or 0x10
        12, 27, 45, 46 -> 0x4 or 0x10
        // 1.67：28 移至上方「直左掉」分支；13 无实拍反证保留「可变直」。
        13 -> 0x1
        14, 29, 47 -> 0x2 or 0x10
        // 1.61 实拍修正：b=57 高德显示「掉头+右转」（诊断行 Back_icon=57
        // 与高德车道条同框确认）。50-56、58+ 暂无实拍样本，落到兜底。
        // 1.69/1.70 实拍修正：b=62 高德车道条实际显示「直行+左转+掉头」
        // 三箭头（白色直行主体 + 深蓝掉头 + 深蓝左转，同框确认）——
        // 1.69 读成「掉头+左转」漏了直行主体，与 b=28 同构。
        57 -> 0x4 or 0x10
        62 -> 0x1 or 0x2 or 0x10
        // 1.75 实拍修正：b=74 高德车道牌显示「掉头+左转」（左转箭头带
        // 掉头回勾，与车道牌同框确认）。64+ 段其余值暂无实拍样本，落到兜底。
        74 -> 0x2 or 0x10
        else -> -1
    }

    /** 1.70：方向掩码诊断缩写（直=↑ 左=← 右=→ 掉头=U） */
    private fun maskToDbg(m: Int): String {
        if (m <= 0) return "无"
        val s = buildString {
            if (m and 0x1 != 0) append("直")
            if (m and 0x2 != 0) append("左")
            if (m and 0x4 != 0) append("右")
            if (m and 0x10 != 0) append("掉")
            if (m and 0x8 != 0) append("稍左")
            if (m and 0x20 != 0) append("稍右")
        }
        return if (s.isEmpty()) "0x" + Integer.toHexString(m) else s
    }

    /**
     * 1.73：文字方向扫描——车道对象里任何短字符串值若含「直/左/右/掉/调」
     * 字样，按字符合成方向掩码。只看值不看键名，限短字符串防误伤。
     * 1.74 定位：该字段是车道属性描述（如「掉头」）而非完整指引——实拍
     * b=17（直+左，与高德一致）同帧文=掉，若优先采用会把左车道画成纯掉头。
     * 因此只作兜底源：位图/枚举/类型都无有效方向时（如 b 值未映射段）启用，
     * 防止落直行兜底。
     */
    private fun textDirMask(o: JSONObject): Int {
        var m = 0
        try {
            for (k in o.keys()) {
                val v = o.opt(k) as? String ?: continue
                if (v.isEmpty() || v.length > 16 || v == "null") continue
                var t = 0
                if ('直' in v) t = t or 0x1
                if ('左' in v) t = t or 0x2
                if ('右' in v) t = t or 0x4
                if ('掉' in v || '调' in v) t = t or 0x10
                if (t != 0) m = m or t
            }
        } catch (_: Throwable) {
        }
        return m
    }

    /**
     * 1.39：车道类型值的字符码形态（AMapLaneInfo.getLaneTypeIdArray）：
     * '0'-'9' 同数字，'A'=10 直+右掉头，'B'=11 左+左掉头，'C'=12 右+右掉头，
     * 'F'=255 不可选。字段若以字符串下发（如 "A"），toIntOrNull 会失败，
     * 之前直接落成直行。
     */
    private fun laneTypeFromRaw(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> {
            val s = raw.trim()
            s.toIntOrNull() ?: when (s.uppercase()) {
                "A" -> 10
                "B" -> 11
                "C" -> 12
                "F" -> 255
                else -> null
            }
        }
        else -> null
    }

    private fun pickStr(d: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = d.optString(k, "").trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    /** 可选整数：存在且可解析才返回（与 pickInt 的 0 兜底语义不同）。 */
    private fun pickOptInt(d: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            if (!d.has(k)) continue
            val v = d.opt(k)
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
            if (n != null) return n
        }
        return null
    }

    /** 车道消息：trafficLaneInfos[]（新）或 drive_way_info[]（hud-probe 归一化后） */
    private fun parseLanes(d: JSONObject): Boolean {
        val bcastArr = d.optJSONArray("drive_way_info")
        val aidlArr = d.optJSONArray("trafficLaneInfos")
        val arr = bcastArr ?: aidlArr ?: return false
        val fromBcast = bcastArr != null
        val now = System.currentTimeMillis()

        // 1.49：广播 13012（drive_way_info，带 Back_icon 真实方向值）是权威数据源；
        // AIDL 30424 在部分版本上是退化值（全 0/1），广播数据活跃时不用 AIDL 覆盖
        if (!fromBcast && now - lastBcastLaneAtMs < 10_000L && state.lanes.isNotEmpty()) {
            state = state.copy(laneMsgAtMs = now)
            return false
        }
        if (fromBcast) lastBcastLaneAtMs = now

        val enabled = if (d.has("drive_way_enabled")) {
            d.optBoolean("drive_way_enabled", true)
        } else {
            d.optBoolean("trafficLaneEnabled", true)
        }

        // 1.36：车道消息心跳。只要高德还在推车道消息（哪怕内容没变）就说明
        // 车道指引还在高德屏幕上；绘制层按这个时间戳做 15 秒过期。
        if (arr.length() == 0 || !enabled) {
            // 高德过完路口会发空车道数组 / 禁用消息：立即清空，不留旧指引
            if (state.lanes.isNotEmpty() || state.laneEnabled) {
                state = state.copy(
                    lanes = emptyList(),
                    laneEnabled = false,
                    lanesAtMs = now,
                    laneMsgAtMs = now
                )
                Log.i(TAG, "车道清空消息：len=${arr.length()} enabled=$enabled")
                return true
            }
            state = state.copy(laneMsgAtMs = now)
            return false
        }

        val lanes = ArrayList<HudLane>(arr.length())
        val rawTypes = ArrayList<String>(arr.length())
        val noDirFlags = ArrayList<Boolean>(arr.length())
        var firstLaneDump = ""
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue

            // 1.39：首条车道对象的完整键值对进诊断行——「全直行」这类问题
            // 多半是字段名或取值形态与预期不符，一张照片即可定位
            // 1.73：键数 10→18、值截断 60 字符——文字方向字段可能在后段
            if (i == 0) {
                firstLaneDump = o.keys().asSequence().take(18).joinToString(" ") { k ->
                    val v = o.opt(k).toString().take(60)
                    "$k=$v"
                }
            }

            val advised = o.optBoolean("trafficLaneAdvised", false) ||
                o.optInt("trafficLaneAdvised", 0) == 1 ||
                o.optBoolean("drive_way_lane_Back_advised", false)
            val number = pickInt(o, "trafficLaneNo", "drive_way_number")

            // 1.50：取值优先级照搬 AMap Companion LaneInfoParser（实测有效）——
            // drive_way_lane_Back_icon（车道箭头位图索引，lane_pdf_0..48）
            // > trafficLaneIcon > trafficLaneType。
            // 1.50：Back_icon 必须走 lane_pdf 位图专用表（逐格对照索引图校准），
            // 它与 LaneAction 枚举只有 0-14 数值巧合一致，15 起语义完全不同；
            // trafficLaneIcon/trafficLaneType 才是枚举，继续走 laneTypeToMask。
            val backRaw = laneTypeFromRaw(o.opt("drive_way_lane_Back_icon"))
            val typeRaw = laneTypeFromRaw(
                o.opt("trafficLaneType") ?: o.opt("laneType")
            )
            val iconRaw = laneTypeFromRaw(o.opt("trafficLaneIcon"))
            // 扩展方向字段（组合方向的位标志）
            val extRaw = laneTypeFromRaw(
                o.opt("trafficLaneExtendedNew") ?: o.opt("drive_way_lane_Extended")
            )
            rawTypes.add("b=$backRaw/t=$typeRaw/i=$iconRaw/e=$extRaw")

            val txtMask = textDirMask(o)
            if (txtMask > 0) rawTypes[rawTypes.size - 1] += "/文=${maskToDbg(txtMask)}"

            // 1.73：未映射位图值（50-56、58-61、63+ 等无实拍样本段）
            // 不再参与方向取值，避免 -1 混入
            val backMapped = backRaw != null && backRaw != 255 && lanePdfToMask(backRaw) >= 0
            val iconMask = if (backMapped) lanePdfToMask(backRaw!!) else 0
            if (backRaw != null && backRaw != 255 && !backMapped) {
                rawTypes[rawTypes.size - 1] += "/b未映射"
            }

            val typeMask = typeRaw?.takeIf { it != 0 }?.let { laneTypeToMask(it) } ?: 0
            val extMask = extRaw?.takeIf { it != 0 }?.let { laneTypeToMask(it) } ?: 0
            // 1.50：次选 trafficLaneIcon——它可能是位图索引也可能是 LaneAction
            // 枚举，两表取非负结果；255（无对应车道）不参与。
            // 1.52：值 >25 只可能是位图索引（枚举值域 0-25），只走位图表，
            // 避免两表对同一取值给出不同方向时摇摆
            val enumMask = iconRaw?.takeIf { it != 0 && it != 255 }?.let {
                if (it > 25) lanePdfToMask(it)
                else maxOf(lanePdfToMask(it), laneTypeToMask(it))
            } ?: 0
            val dirs = when {
                iconMask > 0 -> iconMask
                enumMask > 0 -> enumMask
                typeMask > 0 -> typeMask
                extMask > 0 -> extMask
                // 1.74：文字方向降为兜底——1.73 实拍证实文字字段（如「掉头」）
                // 是车道属性描述而非完整指引：b=17（直+左，与高德一致）同帧
                // 文=掉，文字抢优先级会把左车道画成纯掉头。位图/枚举/类型
                // 都没有有效方向时才用文字，避免未映射 b 值落直行。
                txtMask > 0 -> txtMask
                // 未知取值兜底：枚举/位标志字段直接按位标志解释
                // （1直 2左 4右 8稍左 0x10掉头 0x20稍右）；
                // 1.50：backRaw 是位图索引（如 16=左转），不能当位标志，已移出兜底
                (typeRaw ?: 0) in 1..0x3F -> typeRaw!!
                (iconRaw ?: 0) in 1..0x3F -> iconRaw!!
                (extRaw ?: 0) in 1..0x3F -> extRaw!!
                else -> 0x1
            }
            lanes.add(HudLane(dirs, advised, number, backRaw ?: iconRaw ?: 0))
            // 1.70：诊断行同时透出解析结果——「b=62→直左掉」一张照片即可
            // 同时看到原始值和当前映射，不用再交叉对比高德车道条猜
            rawTypes[rawTypes.size - 1] += "→${maskToDbg(dirs)}"
            // 1.72：记录「无方向信息」车道——方向字段全缺失/为 0，dirs 是
            // 兜底直行而非高德真实意图。注意 b=0（键存在且为 0，
            // lane_pdf_0=直行）是真实直行，不算缺失。
            // 1.73：位图值未映射（lanePdfToMask=-1）同样视为无可靠方向，
            // 纳入防降级保护，不再默默画成直行。
            noDirFlags.add(
                dirs == 0x1 && txtMask == 0 &&
                    (backRaw == null || (backRaw != 255 && !backMapped)) &&
                    (iconRaw ?: 0) == 0 && (typeRaw ?: 0) == 0
            )
        }
        if (lanes.isEmpty()) return false

        // 1.72：防降级合并——实拍证实通道会在「详细形态」（如 b=62→直左掉）
        // 与「无方向简化形态」（方向字段全缺失→兜底纯直行）之间翻转，
        // 简化推送把好的车道指引冲成全直行（HUD 左车道错显直行的真凶）。
        // 新推送与当前车道数一致、当前数据新鲜(<30s)时：无方向信息的
        // 车道保留当前同位车道方向，其余照常更新（advised / number 跟随）。
        var protectedCount = 0
        if (noDirFlags.contains(true) && state.lanes.isNotEmpty() &&
            state.lanes.size == lanes.size && now - state.lanesAtMs < 30_000L
        ) {
            for (i in lanes.indices) {
                if (noDirFlags[i] && lanes[i].directions == 0x1 && state.lanes[i].directions != 0x1) {
                    lanes[i] = lanes[i].copy(directions = state.lanes[i].directions)
                    protectedCount++
                }
            }
        }

        // 全局去重（1.23：从「仅相邻」加强）：高德偶发重复条目是车道重叠的来源之一；
        // 真实的同方向多车道 advised / number 至少有一项不同，不会被误合并
        val deduped = ArrayList<HudLane>(lanes.size)
        val seenKeys = HashSet<Long>()
        for (l in lanes) {
            val key = (l.directions.toLong() and 0xFF) or
                ((if (l.advised) 1L else 0L) shl 8) or
                ((l.number.toLong() and 0xFF) shl 16) or
                ((l.icon.toLong() and 0xFF) shl 24)
            if (!seenKeys.add(key)) continue
            deduped.add(l)
        }

        // 与当前数据相同则不刷新绘制内容，但要推进心跳时间戳并发布：
        // 1.36 起绘制层用心跳判断「高德是否还在显示车道指引」，
        // 心跳不发布就永远到不了绘制层，所以这里必须 return true
        if (deduped == state.lanes && state.laneEnabled) {
            state = state.copy(laneMsgAtMs = now)
            // 1.45：诊断行每条车道消息都刷新（之前早退不更新，
            // 拍诊断行时车道原始值经常是空的，无法校准）
            laneDbg = laneDbgLine(fromBcast, rawTypes, firstLaneDump, protectedCount)
            return true
        }

        val advisedCount = deduped.count { it.advised }
        state = state.copy(
            lanes = deduped,
            laneEnabled = true,
            lanesAtMs = now,
            laneMsgAtMs = now
        )
        Log.i(TAG, "车道更新：${deduped.size} 条（去重前 ${lanes.size}），推荐 $advisedCount 条" +
            if (protectedCount > 0) "（防降级保留 $protectedCount 条）" else "")
        laneDbg = laneDbgLine(fromBcast, rawTypes, firstLaneDump, protectedCount)
        return true
    }

    /** 1.72：诊断行车道行统一拼装（含防降级标记）。 */
    private fun laneDbgLine(fromBcast: Boolean, rawTypes: ArrayList<String>, firstLaneDump: String, protectedCount: Int): String =
        "车道(${if (fromBcast) "广播13012" else "AIDL"})type=[${rawTypes.joinToString(",")}]" +
            (if (protectedCount > 0) "防降级x$protectedCount " else "") +
            "首条={$firstLaneDump}"

    /** 1.31/1.39：转向 / 车道 / 红绿灯原始值分路记录（主界面诊断行显示用）。 */
    private var turnDbg: String = ""
    private var laneDbg: String = ""
    private var lightDbg: String = ""

    /** 1.46：广播通道滚动记录（GaodeWatcher 写入，主界面诊断行显示）。 */
    var bcastDbg: String = ""

    /** 1.64：红绿灯类广播的完整 extras（GaodeWatcher 侧透出，诊断行显示） */
    var lightExtrasDbg: String = ""

    /** 1.53：限速来源诊断（广播 / 深扫命中时记录键值，供实拍校准） */
    var speedDbg: String = ""

    /**
     * 1.53：限速解析入口——命中限速键（数值 km/h）就更新状态。
     * 返回 true 表示状态有变化需要发布。
     */
    /** 1.77：限速上一值来源通道（广播/AIDL），双通道交叉校验用 */
    private var lastSpeedSrc = ""

    fun feedSpeedLimit(value: Int, src: String): Boolean {
        if (value <= 0 || value > 200) return false
        if (value == state.speedLimit) {
            speedDbg = "限速[$src]=$value"
            lastSpeedSrc = src
            return false
        }
        var v = value
        var note = ""
        // 1.77：双通道交叉校验——广播与 AIDL 两路若一路发 km/h、一路发
        // m/s（数值恰差 3.6 倍），会来回覆盖跳变。跨通道且差 3.6 倍时
        // 自动换算统一为 km/h；同通道变化是真实限速区切换，不做换算。
        val sameChannel = (src.startsWith("广播") && lastSpeedSrc.startsWith("广播")) ||
            (src.startsWith("AIDL") && lastSpeedSrc.startsWith("AIDL"))
        val cur = state.speedLimit
        if (!sameChannel && cur in 5..200) {
            if (Math.abs(v * 3.6f - cur) <= 3f) {
                v = Math.round(v * 3.6f)
                note = "（$value m/s→$v km/h）"
            } else if (Math.abs(cur * 3.6f - v) <= 11f) {
                note = "（旧值实为 m/s，已按 $v km/h 修正）"
            }
        }
        state = state.copy(speedLimit = v, speedLimitAtMs = System.currentTimeMillis())
        speedDbg = "限速[$src]=$v${if (lastSpeedSrc.isNotEmpty()) "（上源:$lastSpeedSrc=$cur）" else ""}$note"
        lastSpeedSrc = src
        Log.i(TAG, "限速更新：$v km/h（来源 $src$note）")
        publish()
        return true
    }

    /**
     * 1.53：深度递归扫描 JSON 树找限速字段（键名含 speedlimit / limitspeed /
     * maxspeed，大小写不敏感；纯 speed 不算——那是当前车速）。
     */
    private fun deepScanSpeed(node: Any?, found: MutableMap<String, Int>, depth: Int) {
        if (depth > 5 || found.isNotEmpty()) return
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    val kl = k.lowercase()
                    if ((v is Number || v is String) &&
                        (kl.contains("speedlimit") || kl.contains("limitspeed") ||
                            kl.contains("maxspeed") || kl.contains("speed_max") ||
                            kl.contains("limitspeedvalue"))
                    ) {
                        val n = when (v) {
                            is Number -> v.toInt()
                            else -> (v as String).trim().toIntOrNull() ?: Int.MIN_VALUE
                        }
                        if (n in 5..200) found[k] = n
                    }
                    deepScanSpeed(v, found, depth + 1)
                }
            }

            is org.json.JSONArray -> {
                for (i in 0 until node.length()) deepScanSpeed(node.opt(i), found, depth + 1)
            }

            is String -> {
                val s = node.trim()
                if (s.startsWith("{") && s.endsWith("}")) {
                    try {
                        deepScanSpeed(JSONObject(s), found, depth + 1)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * 1.46：深度递归扫描 JSON 树找红绿灯字段。
     * 数据可能嵌在任意一层，或以字符串形式内嵌（"data":"{\"status\":..}"），
     * 键名匹配大小写不敏感。
     */
    private fun deepScanLight(node: Any?, found: MutableMap<String, Int>, depth: Int) {
        if (depth > 5 || found.size >= 5) return
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    val kl = k.lowercase()
                    if (v is Number || v is String) {
                        val n = when (v) {
                            is Number -> v.toInt()
                            else -> (v as String).trim().toIntOrNull() ?: Int.MIN_VALUE
                        }
                        if (n != Int.MIN_VALUE) {
                            when {
                                // 1.64：status 放宽为包含匹配（lightStatusEx 等变体）
                                kl.contains("lightstatus") -> found["status"] = n
                                kl.contains("redlightcountdown") || kl.contains("redcountdown") -> found["red"] = n
                                kl.contains("greenlastsecond") || kl.contains("greenlightlast") ||
                                    kl.contains("greenlightcountdown") -> found["green"] = n
                                // 1.64：秒数键放宽——lightSecond/lightTime/lightRemain/
                                // 任意 *countdown 后缀（redCountdown/lightCountdown/
                                // trafficLightCountdown 等巡航版可能的变体命名）
                                kl.endsWith("countdown") ||
                                    kl.contains("lightsecond") || kl.contains("lighttime") ||
                                    kl.contains("lightremain") -> found["gen"] = n
                                kl == "dir" && !found.containsKey("dir") -> found["dir"] = n
                            }
                        }
                    }
                    deepScanLight(v, found, depth + 1)
                }
            }

            is org.json.JSONArray -> {
                for (i in 0 until node.length()) deepScanLight(node.opt(i), found, depth + 1)
            }

            is String -> {
                // 字符串内嵌 JSON：尝试解析后递归
                val s = node.trim()
                if (s.startsWith("{") && s.endsWith("}")) {
                    try {
                        deepScanLight(JSONObject(s), found, depth + 1)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * 诊断行：三路原始值合并显示、互不覆盖。
     * 之前各解析器轮流覆盖同一个字符串，车道原始值几乎总是被转向/红绿灯
     * 冲掉，「车道全直行」这类问题拿不到关键数据。
     */
    val debugInfo: String
        get() = listOf(
            laneDbg, turnDbg, lightDbg,
            if (bcastDbg.isNotEmpty()) "广播类型=$bcastDbg" else "",
            if (speedDbg.isNotEmpty()) speedDbg else "",
            if (lightExtrasDbg.isNotEmpty()) lightExtrasDbg else ""
        )
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** GBL ManeuverIconID 中文描述（诊断显示用）。 */
    private fun describeIcon(icon: Int): String = when (icon) {        0x02 -> "左转"
        0x03 -> "右转"
        0x04 -> "稍左"
        0x05 -> "稍右"
        0x06, 0x07 -> "左/右后方(掉头)"
        0x08, 0x13 -> "掉头"
        0x09, 0x14 -> "直行"
        else -> "其他"
    }

    /**
     * 1.78：从多方向倒数列表中选出「本车当前灯组」的值。
     * 规则（按优先级）：
     * 1) 延续锁定：列表中存在 = 上一锁定值-1（递减一秒）或 = 上一锁定值
     *    （同秒重复推送）的元素 → 锁定它，数字保证逐秒平滑递减；
     * 2) 断链同索引：锁定的值从列表消失（相位切换、数值跳变）→ 取列表中
     *    同一索引位的值（同一灯组位置不变），20 秒内有效；
     * 3) 重新起锁：超 20 秒没锁到（过路口灯组换页）或首次 → 取首值。
     * 返回 Pair(选中值, 是否延续锁定) 供诊断行标注。
     */
    private fun selectLockedRemain(list: List<Int>): Pair<Int, Boolean> {
        val cands = list.filter { it > 0 }
        if (cands.isEmpty()) return Pair(-1, false)
        val nowMs = System.currentTimeMillis()
        val fresh = nowMs - lockListAtMs < 20_000L
        if (lockListSec >= 0 && fresh) {
            val contIdx = cands.indexOfFirst { it == lockListSec - 1 || it == lockListSec }
            if (contIdx >= 0) {
                lockListIdx = contIdx
                lockListSec = cands[contIdx]
                lockListAtMs = nowMs
                return Pair(lockListSec, true)
            }
            if (lockListIdx in cands.indices) {
                lockListSec = cands[lockListIdx]
                lockListAtMs = nowMs
                return Pair(lockListSec, false)
            }
        }
        // 首次 / 过期：从首值起锁
        lockListIdx = 0
        lockListSec = cands.first()
        lockListAtMs = nowMs
        return Pair(lockListSec, false)
    }

    /** 红绿灯倒数：兼容多个字段命名 */
    private fun parseTrafficLight(d: JSONObject, messageType: String = "", rawDump: String = ""): Boolean {
        // 1.34：只有确实带红绿灯字段的消息才处理。
        // 之前车道（30424）、引导（30407）、广播车道 JSON 也都会走到这里，
        // 它们不含红绿灯键 → 解析出 status=-1 → 被当成「无红绿灯」立即清空，
        // 随后的红绿灯协议又写回来，两条协议高频交替 = HUD 上红灯反复闪烁。
        val byType = messageType.contains("TrafficLight", true) ||
            messageType.contains("巡航", true)
        val byKey = d.has("trafficLights") || d.has("trafficLightStatus") ||
            d.has("redLightCountDownSeconds") || d.has("greenLightLastSecond") ||
            d.has("countDown") || d.has("waitRound") ||
            d.keys().asSequence().any { k ->
                k.contains("light", true) || k.contains("countdown", true)
            }
        if (!byType && !byKey) return false

        var status = -1
        var seconds = -1

        val lights = d.optJSONArray("trafficLights")
        val lightSrc = lights?.optJSONObject(0) ?: d

        // 显式状态原值（0-6）或字符串 red/green/yellow。
        // 1.49 关键重写：AMap Companion（开源、实测校准）证实这套灯数据的
        // status 编码是 1=红、4=绿、0/2/3/5/6=黄——不是 1红/2绿/3黄！
        val statusRaw = pickOptInt(lightSrc, "trafficLightStatus", "status")
            ?: pickOptInt(d, "trafficLightStatus") ?: -1
        val explicit = if (statusRaw >= 0) {
            statusRaw
        } else {
            val st = lightSrc.optString("trafficLightStatus", lightSrc.optString("status", ""))
                .trim().lowercase()
            when (st) {
                "red", "红" -> 1
                "green", "绿" -> 4
                "yellow", "黄" -> 3
                else -> -1
            }
        }

        val redSec = pickOptInt(lightSrc, "redLightCountDownSeconds", "redLightCountdown", "redCountDown")
            ?: pickOptInt(d, "redLightCountDownSeconds")
        // 1.47：实车广播类型行定位——绿灯秒数键名是 greenLastSecond（不是
        // greenLightLastSecond），之前一直读不到这个最直接的绿灯信号
        val greenSec = pickOptInt(
            lightSrc, "greenLastSecond", "greenLightLastSecond", "greenLightCountdown", "greenCountDown"
        ) ?: pickOptInt(d, "greenLastSecond", "greenLightLastSecond")
        var genSec = pickOptInt(lightSrc, "countDown") ?: pickOptInt(d, "countDown")
        // 1.78：多方向倒数列表——不再固定取首值，按连续性锁定当前灯组
        // （每秒递减的特征值），锁定断链时同索引跟随。列表存在时覆盖
        // 单值 countDown，保证 HUD 显示的是本车当前灯组的倒数。
        var listLocked = false
        val remainList = (pickStr(lightSrc, "countDownList") ?: pickStr(d, "countDownList"))
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (remainList.isNotEmpty()) {
            val sel = selectLockedRemain(remainList)
            if (sel.first > 0) {
                genSec = sel.first
                listLocked = sel.second
            }
        }
        // 1.65：绿灯秒数「恒 3 标志」检测——实车确认这版车机绿灯期间
        // greenLastSecond 固定发 3（绿灯尾提示标志），只在真正剩 3 秒内才
        // 递减 3→2→1。连续两条同值（不递减）即判定为标志值，不能当剩余
        // 秒数显示（否则绿灯全程假"3"常亮），也不能触发黄灯尾判定。
        val greenStuck = greenSec != null && greenSec > 0 && greenSec == prevGreenSec
        // 秒数 = 当前相位剩余秒（redLightCountDownSeconds 各相位都带值，
        // 与 AMap Companion secondsForLight 一致）
        seconds = listOfNotNull(redSec, genSec, greenSec).filter { it > 0 }.maxOrNull() ?: -1

        // 1.76：仅有通用倒数（countDown / EXTRA_TRAFFIC_LIGHT_REMAIN_SECONDS，
        // 无 status / 红绿倒数键）时——巡航版 60075 的典型形态——做相位推断：
        // 沿用上一判定灯色，倒数跳升（新相位开始）红绿互换。之前这条路径
        // 落「黄灯」兜底，巡航灯色错且与倒数脱节。
        val remainOnly = explicit < 1 && redSec == null && greenSec == null && (genSec ?: -1) > 0
        status = if (remainOnly) {
            val nowMs = System.currentTimeMillis()
            if (prevRemainOnlyAtMs > 0 && nowMs - prevRemainOnlyAtMs < 120_000L &&
                prevRemainOnlySec >= 0 && genSec!! > prevRemainOnlySec + 2
            ) {
                // 相位交替：新倒数比上一次明显大 → 新相位开始，红绿互换
                remainPhaseGuess = if (remainPhaseGuess == 1) 2 else 1
            } else if (remainPhaseGuess == 0) {
                remainPhaseGuess = if (state.lightStatus == 2) 2 else 1
            }
            prevRemainOnlySec = genSec!!
            prevRemainOnlyAtMs = nowMs
            remainPhaseGuess
        } else when {
            // 黄灯尾：绿灯只剩 3 秒以内（green==3 且无红倒数，或 1-2 秒）；
            // 1.65：greenStuck（恒 3 标志）不算绿灯尾
            !greenStuck &&
                (((greenSec ?: -1) == 3 && (redSec ?: -1) <= 0) || (greenSec ?: 0) in 1..2) -> 3
            statusRaw == 0 || statusRaw == 2 || statusRaw == 3 || statusRaw == 5 || statusRaw == 6 -> 3
            statusRaw == 4 -> 2
            statusRaw == 1 -> 1
            (greenSec ?: -1) > 0 -> 2
            seconds > 0 -> 3
            else -> -1
        }
        // 1.53：秒数按灯色优先取对应相位的倒数——红灯优先红倒数、绿/黄优先
        // 绿灯倒数（firstOrNull 按优先级顺序取第一个有效值，不再取最大值；
        // 实拍 1.37 证实绿灯时 redLightCountDownSeconds 也可能带更大残留值，
        // 取最大会显示错秒）。gen=countDown 通用倒数作次选。
        // 1.65：greenStuck 时排除绿灯假 3，改用红倒数/通用倒数；绿灯期间
        // 都没有真秒数时显示不带数字的绿灯（画层支持 seconds<0 隐藏数字）。
        seconds = if (remainOnly) genSec!! else when (status) {
            1 -> listOfNotNull(redSec, genSec, greenSec).filter { it > 0 }.firstOrNull() ?: -1
            2, 3 -> {
                val g = if (greenStuck) null else greenSec
                val v = listOfNotNull(g, genSec, redSec).filter { it > 0 }.firstOrNull() ?: -1
                if (v < 0 && status == 2) -1 else v
            }
            else -> listOfNotNull(redSec, genSec, greenSec).filter { it > 0 }.firstOrNull() ?: -1
        }
        // 1.65：维护递减检测基准（1.47 的遗留字段此前从未更新）
        prevGreenSec = greenSec ?: 0
        prevRedSec = redSec ?: 0
        prevLightSec = seconds
        prevLightAtMs = System.currentTimeMillis()

        val lightMask = pickOptInt(
            lightSrc, "lightIcon", "turnIcon", "trafficLightIcon",
            "arrowIcon", "turnType", "directionIcon"
        )?.let { turnMaskFromIcon(it) } ?: 0
        // 1.49：dir = 路口行驶方向（红绿灯胶囊内箭头），按 AMap Companion
        // 实测表解析：0=掉头 1=左转 2/3=右转 4=直行 5/6=左前斜 7/8=右前斜。
        // 之前按 GBL ManeuverIconID 解析是错的——高德直行时 dir=4 被我们
        // 画成稍左、左转时 dir=1 落到未知，两轮实拍错位都源于此。
        // 导航模式下 dir=0（掉头）高德显示直行（Companion 同款归一化）。
        val dirRaw = pickOptInt(lightSrc, "dir") ?: 4
        val dirMask = pickOptInt(lightSrc, "dir")?.let { lightDirToMask(it) } ?: 0
        // 诊断行（主界面显示）：原始值全量透出，一张照片即可校准
        val srcLabel = if (messageType.startsWith("Broadcast")) {
            "广播" + messageType.removePrefix("Broadcast")
        } else if (messageType.contains("TrafficLight", true)) {
            "AIDL"
        } else {
            messageType.take(12)
        }
        // 1.76：多方向倒数列表透出（巡航分组倒计时的数组形态）
        // 1.78：锁定标记——「列表=41,36 锁36」表示当前灯组锁定在 36
        val listDump = pickStr(lightSrc, "countDownList")
        lightDbg = "红绿灯[$srcLabel]" +
            (if (rawDump.isNotEmpty()) "原始=$rawDump → " else "") +
            (if (listDump != null) "列表=$listDump${if (listLocked) " 锁$genSec" else ""} " else "") +
            "st=$explicit r=$redSec g=$greenSec gen=$genSec dir=${pickOptInt(lightSrc, "dir") ?: -1} " +
            "wr=${pickOptInt(lightSrc, "waitRound") ?: -1} → 判定=${if (status in 1..3) status else "无"}"

        // 有效状态（1 红 / 2 绿 / 3 黄）
        if (status > 0) {            // 秒数缺失时沿用上一次的值（高德有时只发状态不发倒数），
            // 不要落成 -1 / 0，否则画面会跳一下。
            // 1.65：绿灯例外——绿灯期间没有真秒数（greenLastSecond 恒 3
            // 标志、红倒数/通用倒数也无）时不沿用红灯尾的残留值（沿用会
            // 显示「绿 1」之类的假倒数），落 -1 让画层只画绿灯不画数字。
            val sec = when {
                seconds >= 0 -> seconds
                status == 2 -> -1
                else -> state.lightSeconds.coerceAtLeast(0)
            }
            // 1.65：圈内箭头方向三级兜底——dir（60075 原生，巡航版恒 -1）
            // > lightIcon > 推荐车道方向（本车拟行方向 ≈ 推荐车道方向，
            // 高德车道指引的 advised 车道就是当前要走的道）。之前无 dir
            // 一律画直行箭头，左拐 / 掉头路口全部错显直行。
            val advisedMask = state.lanes.firstOrNull { it.advised }?.directions ?: 0
            val effMask = when {
                dirMask != 0 -> dirMask
                lightMask != 0 -> lightMask
                advisedMask != 0 -> advisedMask
                else -> 0
            }
            // 1.60：按行驶方向分行记录（巡航两行显示：直行一行、左转一行）。
            // 同一路口不同 dir 的 60075 / AIDL 消息各更新各自一行，60 秒
            // 没刷新的方向行自动剔除。
            val rowDir = if (dirRaw in 0..8) dirRaw else 4
            lightRows[rowDir] = CruiseLight(rowDir, status, sec, effMask, System.currentTimeMillis())
            val rowsSnapshot = cruiseLightSnapshot()
            if (status == state.lightStatus && sec == state.lightSeconds && effMask == state.lightTurnMask &&
                rowsSnapshot == state.cruiseLights
            ) return false
            state = state.copy(
                lightStatus = status,
                lightSeconds = sec,
                lightTurnMask = effMask,
                lightAtMs = System.currentTimeMillis(),
                cruiseLights = rowsSnapshot
            )
            Log.i(TAG, "红绿灯更新：status=$status 倒数=$sec 灯方向=$lightMask dir=$dirRaw")
            return true
        }

        // 无有效状态：高德「前方无红绿灯」发的是 0。
        // 加 1.2 秒去抖——刚拿到有效数据时的一次抖动不清空，避免闪一下。
        if (state.lightStatus != -1 || state.lightSeconds != -1) {
            val age = System.currentTimeMillis() - state.lightAtMs
            if (age < 1200L) return false
            lightRows.clear()
            // 1.78：灯组清空（过路口）时同步释放倒数锁定，下一路口重新起锁
            lockListIdx = -1
            lockListSec = -1
            state = state.copy(
                lightStatus = -1,
                lightSeconds = -1,
                lightTurnMask = 0,
                lightAtMs = System.currentTimeMillis(),
                cruiseLights = emptyList()
            )
            Log.i(TAG, "红绿灯清空：status=$status 倒数=$seconds（前方无红绿灯）")
            return true
        }
        return false
    }

    private fun pickInt(o: JSONObject, vararg keys: String): Int {
        for (k in keys) {
            if (o.has(k)) {
                val v = o.opt(k)
                if (v is Number) return v.toInt()
                if (v is String) v.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun publish() {
        val snapshot = state
        onState(snapshot)
    }
}
