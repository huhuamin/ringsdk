package com.servicesdk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import com.android.ktx.view.onClick
import com.blankj.utilcode.util.NumberUtils
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.UriUtils
import com.eiot.aizo.ext.otherwise
import com.eiot.aizo.ext.yes
import com.eiot.aizo.sdk.callback.AizoDeviceConnectCallback
import com.eiot.aizo.util.timer.Interval
import com.eiot.aizo.util.timer.IntervalStatus
import com.eiot.be.algorithm.ActivityScore
import com.eiot.be.algorithm.HealthScore
import com.eiot.be.algorithm.ReadinessScore
import com.eiot.be.algorithm.SleepScore
import com.eiot.ringsdk.ServiceSdkCommandV2
import com.eiot.ringsdk.base.LocalSave
import com.eiot.ringsdk.be.DeviceManager
import com.eiot.ringsdk.bean.*
import com.eiot.ringsdk.callback.*
import com.eiot.ringsdk.ecg.ECGCallback
import com.eiot.ringsdk.ext.logBx
import com.eiot.ringsdk.ext.logIx
import com.eiot.ringsdk.ext.notNullAndEmpty
import com.eiot.ringsdk.ext.secondToTime
import com.eiot.ringsdk.ext.toJsonString
import com.eiot.ringsdk.heartrate.MeasureTimeCallback
import com.eiot.ringsdk.heartrate.MeasureTimeData
import com.eiot.ringsdk.measure.ContinuousMeasureData
import com.eiot.ringsdk.measure.ContinuousMeasureListener
import com.eiot.ringsdk.measure.MeasureResult
import com.eiot.ringsdk.measure.MeasureResultCallback
import com.eiot.ringsdk.measure.MeasureStatus
import com.eiot.ringsdk.measure.MeasureStatusCallback
import com.eiot.ringsdk.userinfo.UserInfo
import com.eiot.ringsdk.userinfo.UserInfoCallback
import com.eiot.ringsdk.util.TimeUtil
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.jiaqiao.product.ext.click
import com.jiaqiao.product.ext.launchMain
import com.jiaqiao.product.ext.resString
import com.jiaqiao.product.ext.toAppInfo
import com.jiaqiao.product.ext.toFastJson
import com.jiaqiao.product.ext.toMoshiString
import com.servicesdk.base.BaseVBActivity
import com.servicesdk.demo.R
import com.servicesdk.demo.databinding.ActivityMainBinding
import com.servicesdk.ext.checkConnectClick
import com.servicesdk.ext.visOrGone
import com.servicesdk.util.CallManager
import com.servicesdk.util.CallManager.hasCallPermission
import com.servicesdk.util.MoodAlgorithm
import com.servicesdk.util.PermissionUtil
import com.servicesdk.util.SaveInfo
import com.servicesdk.util.SaveInfo.gluRegister
import com.servicesdk.view.TextAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit


class MainActX : BaseVBActivity<ActivityMainBinding>() {

    private val intervalAda by lazy { TextAdapter() }

    private val healthDataAda by lazy { TextAdapter() }

//    private val sleepDetailAda by lazy { TextAdapter() }

//    private val sleepTotalAda by lazy { TextAdapter() }

    private val sleepDataAda by lazy { TextAdapter() }

    private val sportRecordAda by lazy { TextAdapter() }

    private val healthScoreAda by lazy { TextAdapter() }

    private val beSearchAda by lazy { TextAdapter() }

    private val measureDataAda by lazy { TextAdapter() }

    /**
     * 运动
     */
    val WALK = 0x09    //室外健走
    val RUN = 0x0A    //室外跑步
    val BIKE = 0x0B    //室外骑行
    val INDOOR_BIKE = 0x07  //室内健走
    val INDOOR_RUN = 0x08 //室内跑步
    val INDOOR_WALK = 0x05  //室内骑行
    private val sportList = mutableListOf(WALK, INDOOR_WALK, RUN, INDOOR_RUN, BIKE, INDOOR_BIKE)
    private var sportStartTime = 0L     //运动开始时间
    private var pauseStartTime = 0L     //最后一次暂停开始时间
    private var itemInterval = 5L       //运动数据实时计算间隔
    private var sportType = WALK
    private var sportTypePosition = 0
    var km: Double = 0.0
    var kcal: Double = 0.0
    private var isPause = false
    private var realStep = 0
    private var useTime = 0L          //运动时长，单位：秒
    private var pauseTime = 0L        //暂时时长，单位：秒
    private var sportId = 0L

    private var measureECGCountDownJob: Job? = null

    private var deviceFirmwareParams: FirmwareParams? = null
    private var deviceOtaInfo: DeviceOtaInfo? = null

    val sportTimer: Interval by lazy {
        Interval(-1, 1, TimeUnit.SECONDS, 0).life(this, Lifecycle.Event.ON_DESTROY)
    }


    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult? ->
            if (result?.resultCode == Activity.RESULT_OK) {
                getOtaFile.launch("*/*")
            }
            mViewBind.btOtaUpdate.isEnabled = true
        }


    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (PermissionUtil.hasBeSearchPermission(this)) {
                PermissionUtil.isOpenLocation(this)
            } else {
                //locationFailDialog.show()
                "没有定位权限".logBx()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }

    private val phoneStateRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCallPermission(this@MainActX)) {
                "用户已经授予权限，开始监听电话状态".logBx()
                CallManager.checkAndRegisterCallListener(this@MainActX)
            } else {
                "用户拒绝权限。不能监听电话状态:${shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)}".logBx()
            }
        }


    val getOtaFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // 处理返回的 Uri
        uri?.let {
            ServiceSdkCommandV2.startDeviceOtaUpdate(UriUtils.uri2File(uri).absolutePath, object :
                DeviceOtaResultCallback {
                override fun onSuccess() {
                    "升级成功".logIx()
                    mViewBind.txOtaInfo.text = R.string.str_device_ota_successful.resString()
                    mViewBind.btOtaUpdate.isEnabled = true
                }

                override fun onFail(state: Int) {
                    mViewBind.txOtaInfo.text = R.string.str_device_ota_fail.resString() + state
                    mViewBind.btOtaUpdate.isEnabled = true
                }

                override fun onProgress(progress: Int) {
                    mViewBind.txOtaInfo.text = R.string.str_device_ota_progress.resString() + progress
                }
            })
        }?:let {
            ToastUtils.showLong(R.string.str_common_param_error.resString())
            mViewBind.btOtaUpdate.isEnabled = true
        }

    }

    private val connectCallback by lazy {
        object : AizoDeviceConnectCallback {
            override fun connect() {
                "蓝牙回调已经连接".logIx()
                connectUI()
                ServiceSdkCommandV2.notifyBoundDevice(
                    deviceName = "",
                    deviceMac = DeviceManager?.mac!!,
                ) { r ->
                    r.yes {
                        //ServiceSdkCommandV2.requestConnectionPriority()
                        "notifyBoundDevice true".logIx()
                    }
                }
            }

            override fun disconnect() {
                disconnectUI()
            }

            override fun connectError(throwable: Throwable, state: Int) {
                errorUI(throwable, state)
            }
        }
    }

    override fun initView(savedInstanceState: Bundle?) {
        parse()
        click()
    }

    fun parse() {
        mViewBind.macAddress.setText(if (SaveInfo.mac.isNullOrEmpty()) "D0:9F:D9:D2:54:55" else SaveInfo.mac)

        mViewBind.rvHeartRateIntervalList.adapter = intervalAda
        mViewBind.rvHealthData.adapter = healthDataAda
//        mViewBind.rvSleepDetail.adapter = sleepDetailAda
//        mViewBind.rvSleepTotal.adapter = sleepTotalAda
        mViewBind.rvSleepData.adapter = sleepDataAda
        mViewBind.rvSportData.adapter = sportRecordAda
        mViewBind.rvScores.adapter = healthScoreAda
        mViewBind.itemBeSearch.rvDevices.adapter = beSearchAda
        mViewBind.itemContinuousMeasure.rvDatas.adapter = measureDataAda

        sportRecordAda.setList(emptyList())

        // 假设你有以下字符串数组作为数据源
        val options = arrayOf(
            R.string.outdoor_walking.resString(),
            R.string.indoor_walking.resString(),
            R.string.outdoor_running.resString(),
            R.string.indoor_running.resString(),
            R.string.outdoor_cycling.resString(),
            R.string.indoor_cycling.resString()
        )

        // 创建ArrayAdapter并将数据源绑定到Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉菜单的布局
        mViewBind.spSportType.adapter = adapter

        // 你可以添加一个点击监听器，但这不是必要的，因为Spinner默认在点击时会显示下拉菜单
        mViewBind.spSportType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position).toString()
                // 这里处理选中的项
                sportTypePosition = position
                sportType = sportList[position]
                mViewBind.tvUsedTime.text = 0.toLong().secondToTime()
                //根据运动类型显示距离和配速
                mViewBind.llStep.visOrGone(sportType != BIKE && sportType != INDOOR_BIKE)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 如果不需要处理未选择项的情况，可以忽略此方法
                ToastUtils.showLong(R.string.unknown_sport_type.resString())
            }
        }


        //Battery
        ServiceSdkCommandV2.registerPowerStateListener { bean ->
            bean.toString().logIx("电量信息")
            mViewBind.txBatteryInfo.text =
                R.string.str_device_battery.resString() + bean.electricity + "%" + "\n" + R.string.str_device_battery_charge_status.resString() + when (bean.workingMode) {
                    0 -> R.string.str_device_battery_uncharge.resString()
                    1 -> R.string.str_device_battery_charging.resString()
                    else -> R.string.str_device_battery_uncharge.resString()
                }
        }
        //SOS
//        ServiceSdkCommandV2.getDeviceSos(object : DeviceSosCallback {
//            override fun deviceSos(bean: DeviceSos) {
//                mViewBind.sosSbv.setCheckedNoAction(bean.sosSwitch == 1)
//            }
//        })

        //DeviceConfig
        ServiceSdkCommandV2.addDeviceConfigCallback { bean ->
            bean.isTouchSet.logIx("isTouchSet")
            mViewBind.txDeviceInfo.text =
                ("bloodOxygenMonitoring:" + bean.bloodOxygenMonitoring + "\n"
                        + "bloodPressureMonitoring:" + bean.bloodPressureMonitoring + "\n"
                        + "bloodSugarMonitoring:" + bean.bloodSugarMonitoring + "\n"
                        + "breatheMonitoring:" + bean.breatheMonitoring + "\n"
                        + "ecgMonitoring:" + bean.ecgMonitoring + "\n"
                        + "heartRateMonitoring:" + bean.heartRateMonitoring + "\n"
                        + "isHeartRateSupport:" + bean.isHeartRateSupport + "\n"
                        + "isTouchSet" + bean.isTouchSet + "\n"
                        + "pressureMonitoring:" + bean.pressureMonitoring + "\n"
                        + "sleepMonitoring:" + bean.sleepMonitoring + "\n"
                        + "sosTriggerMode:" + bean.sosTriggerMode + "\n"
                        + "supportSos:" + bean.supportSos + "\n"
                        + "supportWakeupByGesture：" + bean.supportWakeupByGesture + "\n"
                        + "temperatureMonitoring:" + bean.temperatureMonitoring + "\n"
                        + "support vibrate:" + bean.vibrate
                        )
        }


        //监听智能触控事件
        val mSmartTouchEventCallback = object : SmartTouchEventCallback {
            override fun onSmartTouchEvent(values: List<SmartTouchEventModel>) {
                "智能触控事件  $values".logBx()

                values.forEach {
                    when(it.event) {
                        259 -> {
                            ToastUtils.showLong(getString(R.string.smart_touch_event_long_press,
                                TimeUtil.getTimeStr2(it.time)))
                        }
                        260 -> {
                            ToastUtils.showLong(getString(R.string.smart_touch_event_slide_up,TimeUtil.getTimeStr2(it.time)))
                        }
                        261 -> {
                            ToastUtils.showLong(getString(R.string.smart_touch_event_slide_down,TimeUtil.getTimeStr2(it.time)))
                        }
                    }
                }
            }
        }
        ServiceSdkCommandV2.addSmartTouchEventListener(mSmartTouchEventCallback)
        //ServiceSdkCommandV2.removeSmartTouchEventListener(mSmartTouchEventCallback)
    }

    private fun click() {
        mViewBind.connect.onClick {
            XXPermissions.with(this)
                .permission(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>?, all: Boolean) {
                        if (all) {
                            val macStr = mViewBind.macAddress.text.toString()
                            SaveInfo.mac = macStr
                            (macStr.isNullOrEmpty()).yes {
                                ToastUtils.showLong(R.string.str_device_mac_null.resString())
                            }.otherwise {
                                DeviceManager.isConnect().yes {
                                    ServiceSdkCommandV2.disconnect { r ->
                                        r.yes {
                                            mViewBind.connect.isEnabled = true
                                            ServiceSdkCommandV2.removeCallback(connectCallback)
                                            disconnectUI()
                                        }
                                    }
                                }.otherwise {
                                    ServiceSdkCommandV2.addCallback(connectCallback)
                                    ServiceSdkCommandV2.connect(macStr)
                                    mViewBind.connect.isEnabled = false
                                }
                            }
                        } else {
                            mViewBind.connect.isEnabled = false
                        }
                    }

                    override fun onDenied(permissions: MutableList<String>?, never: Boolean) {
                        super.onDenied(permissions, never)
                        if (never) {
                            toAppInfo()
                        }
                    }
                })

        }

        mViewBind.btSdkInit.onClick {
            mViewBind.btSdkInit.isEnabled = false
//            var appName = mViewBind.etAppName.text?.trim().toString()
//            var appPackageId = mViewBind.etAppPackageid.text?.trim().toString()
//            var appVersion = mViewBind.etAppVersion.text?.trim().toString()
//            var country = mViewBind.etCountry.text?.trim().toString()
//            var language = mViewBind.etLanguage.text?.trim().toString()
            var btConnectId = mViewBind.etBluetoothConnectid.text?.trim().toString()
//            "Init AIZORING Sdk, App_Name=${appName}, App_Package_Name=${appPackageId}, App_Version=${appVersion}, Country=${country}, Language=${language}, Bluetooth_ConnectId=${btConnectId}".logIx()

            if (btConnectId.isNullOrEmpty()) {
                ToastUtils.showLong(R.string.bt_connectid_error_null.resString())
            } else if (btConnectId.length != 32) {
                ToastUtils.showLong(R.string.bt_connectid_error_not32.resString())
            } else {
                val result = ServiceSdkCommandV2.setBtParm (
                    btConnectId
                )
                if (result == false) {
                    ToastUtils.showLong(R.string.bt_connectid_set_fail.resString())
                } else {
                    ToastUtils.showLong(R.string.bt_connectid_set_success.resString())
                }
            }
            mViewBind.btSdkInit.isEnabled = true
        }

        mViewBind.itemBeSearch.btnBeStartSearch.onClick {
            if (PermissionUtil.hasBeSearchPermission(this)) {
                if (PermissionUtil.isOpenLocation(this)) {
                    ServiceSdkCommandV2.searchBtDevice(
//                        this,
//                        15,
//                        filterNameList = listOf("AIZO RING"),
//                        keepOnce = false,
                        lis = object : OnBluetoothSearchListener {
                            override fun end() {
                                beSearchAda.addData("扫描结束")
                                mViewBind.itemBeSearch.rvDevices.scrollToPosition(beSearchAda.itemCount - 1)
                            }

                            override fun search(bluetoothDevice: ExBluetoothDevice) {

                                beSearchAda.addData(
                                    "名称: ${bluetoothDevice.name} \n" +
                                    "地址: ${bluetoothDevice.address} \n" +
                                    "信号值: ${bluetoothDevice.scanResult?.rssi}"
                                )
                                mViewBind.itemBeSearch.rvDevices.scrollToPosition(beSearchAda.itemCount - 1)
                            }

                            override fun start() {
                                beSearchAda.setList(emptyList())
                                beSearchAda.addData("扫描开始")
                            }
                        }
                    )
                } else {
                    "没有定位权限".logBx()
                    beSearchAda.setList(emptyList())
                    beSearchAda.addData("没有定位权限")
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            } else {
                "没有蓝牙权限".logBx()
                beSearchAda.setList(emptyList())
                beSearchAda.addData("没有蓝牙权限")
                requestPermission()
            }
        }

        mViewBind.itemBeSearch.btnBeStopSearch.onClick {
            ServiceSdkCommandV2.stopSearchBtDevice()
        }


        //连续实时测量 - Start -
        mViewBind.itemContinuousMeasure.btnMeasureStart.onClick {
            DeviceManager.isConnect().yes {
                var measureType = mViewBind.itemContinuousMeasure.etMeasureType.text.toString()
                var timeSeconds = mViewBind.itemContinuousMeasure.etMeasureTimeout.text.toString()

                (measureType.isNullOrEmpty()).yes {
                    ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                }.otherwise {
                    var timeout = 0
                    (measureType.isNullOrEmpty()).yes {
                        timeout = 0
                    }.otherwise {
                        timeout = timeSeconds.toInt()
                    }

                    ServiceSdkCommandV2.startContinuousMeasure(
                        listener = object : ContinuousMeasureListener {

                            override fun onStart(result: Int) {
                                result.logIx("Start measure result")
                                ToastUtils.showLong("${R.string.str_measure_start_result.resString()}: ${result}")
                                measureDataAda.setList(emptyList())
                                measureDataAda.addData("Started")
//                                mViewBind.itemContinuousMeasure.rvDatas.requestLayout()
                                mViewBind.itemContinuousMeasure.rvDatas.post {
                                    mViewBind.itemContinuousMeasure.rvDatas.smoothScrollToPosition( measureDataAda.itemCount - 1 )
                                }
                            }

                            override fun onEnd(result: Int) {
                                result.logIx("Stop measure result")
                                ToastUtils.showLong("${R.string.str_measure_stop_result.resString()}: ${result}")
                                measureDataAda.addData("Stopped")
//                                mViewBind.itemContinuousMeasure.rvDatas.requestLayout()
                                mViewBind.itemContinuousMeasure.rvDatas.post {
                                    measureDataAda.notifyDataSetChanged()
                                    mViewBind.itemContinuousMeasure.rvDatas.smoothScrollToPosition( measureDataAda.itemCount - 1 )
                                }
                            }

                            override fun onReceiveData(data: ContinuousMeasureData) {
                                logIx("Receive measure data.")
                                when (data) {
                                    is ContinuousMeasureData.PpiRawData -> {
                                        if (data.sampleDatas.notNullAndEmpty()) {
                                            var dataOutput = ""
                                            data.sampleDatas.forEach { it ->
                                                dataOutput += "Time: ${it.time}, PPI: ${it.ppi} \n"
                                                //measureDataAda.addData("Time: ${it.time}, PPI: ${it.ppi} \n")
                                            }
                                            measureDataAda.addData(dataOutput)
//                                            mViewBind.itemContinuousMeasure.rvDatas.requestLayout()
                                            mViewBind.itemContinuousMeasure.rvDatas.post {
                                                measureDataAda.notifyDataSetChanged()
                                                mViewBind.itemContinuousMeasure.rvDatas.smoothScrollToPosition( measureDataAda.itemCount - 1 )
                                            }
                                        }
                                    }
                                    is ContinuousMeasureData.LovingRawData -> {
                                        if (data.sampleDatas.notNullAndEmpty()) {
                                            var dataOutput = ""
                                            dataOutput += "Measure Data：StartTime=${data.startTime}, Frequency=${data.frequency} \n"
                                            //measureDataAda.addData("Measure Data：StartTime=${data.startTime}, Frequency=${data.frequency} \n")
                                            data.sampleDatas.forEach { it ->
                                                dataOutput += "    Time: ${it.time}, HR: ${it.hr}, Temperature: ${it.temp}, Axis_x: ${it.axis_x}, Axis_y: ${it.axis_y}, Axis_z: ${it.axis_z} \n"
                                                //measureDataAda.addData("    Time: ${it.time}, HR: ${it.hr}, Temperature: ${it.temp}, Axis_x: ${it.axis_x}, Axis_y: ${it.axis_y}, Axis_z: ${it.axis_z} \n")
                                            }
                                            measureDataAda.addData(dataOutput)
//                                            mViewBind.itemContinuousMeasure.rvDatas.requestLayout()
                                            mViewBind.itemContinuousMeasure.rvDatas.post {
                                                measureDataAda.notifyDataSetChanged()
                                                mViewBind.itemContinuousMeasure.rvDatas.smoothScrollToPosition( measureDataAda.itemCount - 1 )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        measureType = measureType.toInt(),
                        timeout = timeout
                    )
                }
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.itemContinuousMeasure.btnMeasureStop.onClick {
            DeviceManager.isConnect().yes {
                var measureType = mViewBind.itemContinuousMeasure.etMeasureType.text.toString()
                (measureType.isNullOrEmpty()).yes {
                    ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                }.otherwise {
                    ServiceSdkCommandV2.stopContinuousMeasure(measureType.toInt())
                }
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.itemContinuousMeasure.btnMeasureQuery.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getMeasureStatus(object: MeasureStatusCallback {
                    override fun result(status: MeasureStatus) {
                        status.toJsonString().logIx("测量状态")
                        mViewBind.itemContinuousMeasure.txMeasureStatus.text =
                            "${R.string.str_device_battery_charge_status.resString()} ${status.status} \n" +
                            "${R.string.str_device_measure_result_type.resString()} ${status.type} \n" +
                            "${R.string.str_device_measure_result_time.resString()} ${TimeUtil.getTimeStr1(status.startTime)}"
                    }
                })
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }
        //连续实时测量 - End -

        mViewBind.btnUpdatePower.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getInstantPowerState()
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.btnFirmwareInfo.onClick {
            DeviceManager.isConnect().yes {
                deviceFirmwareParams = ServiceSdkCommandV2.getFirmwareParams()
                mViewBind.txFirmwareInfoDetail.text = deviceFirmwareParams.toString()
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.btnOtaqueryTitle.onClick {
            DeviceManager.isConnect().yes {
                if (deviceFirmwareParams != null) {
                    ServiceSdkCommandV2.getDeviceOtaInfo(
                        deviceFirmwareParams!!,
                        object : DeviceOtaQueryCallback {
                            override fun OnDeviceOtaQueryResponse(bean: DeviceOtaInfo) {
                                deviceOtaInfo = bean
                                mViewBind.txOtaqueryInfo.text = deviceOtaInfo.toString()
                            }
                        }
                    )
                } else {
                    ToastUtils.showLong(R.string.str_device_ota_query_tip.resString())
                }
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.starMeasure.onClick {
            DeviceManager.isConnect().yes {
                mViewBind.etType.text.isNullOrEmpty().yes {
                    ToastUtils.showLong(R.string.str_common_param_error.resString())
                }.otherwise {
                    /**
                     * state :0x01 启动 0x02 停止/取消
                     * */
                    mViewBind.starMeasure.isEnabled = false
                    var type = mViewBind.etType.text.toString().toInt()
                    type.logIx("测量类型")
                    ServiceSdkCommandV2.instantMeasurement(type, operation = 1, object :
                        MeasureResultCallback {
                        override fun measureResult(bean: MeasureResult) {
                            mViewBind.tvResult.text = "${R.string.str_device_measure_result.resString()} ${bean.result}"
                            mViewBind.tvType.text = "${R.string.str_device_measure_result_type.resString()} ${bean.type}"
                            mViewBind.tvTime.text = "${R.string.str_device_measure_result_time.resString()} ${TimeUtil.getTimeStr2(bean.time)}"
                            mViewBind.tvHeartrate.text = "${R.string.str_device_measure_result_heart_rate.resString()} ${bean.heartrate} bpm"
                            mViewBind.tvBo.text = "${R.string.str_device_measure_result_bo.resString()} ${bean.bloodoxygen} %"
                            mViewBind.tvTp.text = "${R.string.str_device_measure_result_temp.resString()} ${bean.bodytemp}" + getString(R.string.temp_unit)
                            mViewBind.tvSou.text = "${R.string.str_device_measure_result_sou.resString()} ${bean.envtemp}" + getString(R.string.temp_unit)
                            mViewBind.tvPressure.text = "${R.string.str_device_measure_result_pressure.resString()} ${bean.stress}"
                            mViewBind.starMeasure.isEnabled = true
                        }
                    })
                }
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }


        mViewBind.stopMeasure.onClick {
            DeviceManager.isConnect().yes {
                mViewBind.etType.text.isNullOrEmpty().yes {
                    ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                }.otherwise {
                    /**
                     * state :0x01 启动 0x02 停止/取消
                     * */
                    mViewBind.starMeasure.isEnabled = false
                    var type = mViewBind.etType.text.toString().toInt()
                    type.logIx("测量类型")
                    ServiceSdkCommandV2.instantMeasurement(type, operation = 2, object :
                        MeasureResultCallback {
                        override fun measureResult(bean: MeasureResult) {
                            mViewBind.tvResult.text = "${R.string.str_device_measure_result.resString()} ${bean.result}"
                            mViewBind.tvType.text = "${R.string.str_device_measure_result_type.resString()} ${bean.type}"
                            mViewBind.tvTime.text = "${R.string.str_device_measure_result_time.resString()} ${TimeUtil.getTimeStr2(bean.time)}"
                            mViewBind.tvHeartrate.text = "${R.string.str_device_measure_result_heart_rate.resString()} ${bean.heartrate} bpm"
                            mViewBind.tvBo.text = "${R.string.str_device_measure_result_bo.resString()} ${bean.bloodoxygen} %"
                            mViewBind.tvTp.text = "${R.string.str_device_measure_result_temp.resString()} ${bean.bodytemp}" + getString(R.string.temp_unit)
                            mViewBind.tvSou.text = "${R.string.str_device_measure_result_sou.resString()} ${bean.envtemp}" + getString(R.string.temp_unit)
                            mViewBind.tvPressure.text = "${R.string.str_device_measure_result_pressure.resString()} ${bean.stress}"
                            mViewBind.starMeasure.isEnabled = true
                        }

                    })
                }
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }

        mViewBind.btOtaUpdate.onClick {
            mViewBind.btOtaUpdate.isEnabled = false
            DeviceManager.isConnect().yes {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        getOtaFile.launch("*/*")
                    } else {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    XXPermissions.with(this)
                        .permission(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                        )
                        .request(object : OnPermissionCallback {
                            override fun onGranted(permissions: MutableList<String>?, all: Boolean) {
                                if (all) {
                                    getOtaFile.launch("*/*")
                                } else {
                                    "${R.string.str_device_ota_choose_file_permission_fail.resString()}".logIx()
                                }
                            }

                            override fun onDenied(permissions: MutableList<String>?, never: Boolean) {
                                super.onDenied(permissions, never)
                                if (never) {
                                    toAppInfo()
                                }
                            }
                        })
                }
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
                mViewBind.btOtaUpdate.isEnabled = true
            }
        }

        mViewBind.btGetHeartRateInterval.onClick {
            ServiceSdkCommandV2.getDeviceMeasureTime(object : MeasureTimeCallback {
                override fun measureTime(bean: MeasureTimeData) {
                    mViewBind.rvHeartRateIntervalList.visOrGone(true)
                    intervalAda.data.isNotEmpty().yes { intervalAda.data.clear() }
                    ("${R.string.str_device_current_heart_rate_interval.resString()}" + bean.currentInterval + ";" + "${R.string.str_device_default_heart_rate_interval.resString()}" + bean.defaultInterval + ";" + "${R.string.str_device_heart_rate_interval_list.resString()}" + bean.intervalList.size).logIx()
                    mViewBind.txHeartRateIntervalInfo.text =
                        "${R.string.str_device_current_heart_rate_interval.resString()}" + bean.currentInterval + "${R.string.str_device_default_heart_rate_interval.resString()}" + bean.defaultInterval + ";" + "${R.string.str_device_heart_rate_interval_list.resString()}" + bean.intervalList.size

                    var result = bean.intervalList?.map {
                        "time:$it"
                    }?.toMutableList()
                    result?.let {
                        intervalAda.addData(it)
                    }
                }
            })
        }
        mViewBind.btSetHeartRateInterval.onClick {
            var time = mViewBind.etHeartRateInterval.text.toString()
            (time.isNullOrEmpty()).yes {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
            }.otherwise {
                ServiceSdkCommandV2.setDeviceMeasureTime(time.toInt(), object : BCallback {
                    override fun result(r: Boolean) {
                        r.yes {
                            ToastUtils.showLong("${R.string.str_common_set_successful.resString()}")
                        }
                    }

                })
            }
        }
        mViewBind.btDeviceSmartTouchInfo.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getDeviceSmartTouchMode(object : DeviceSmartTouchModeCallback {
                    override fun deviceSmartTouch(bean: DeviceSmartTouchMode) {
                        ("${R.string.str_device_touch_mode_result.resString()}" + bean.touchAppSwitch + ";" + "mode" + bean.touchMode).logIx()
                        mViewBind.txDeviceSmartTouchInfo.text =
                            "${R.string.str_device_touch_mode_result.resString()}" + bean.touchAppSwitch + ";" + "${R.string.str_device_set_touch_mode_edit_hint.resString()}" + bean.touchMode
                    }

                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }

        }
        mViewBind.btSetDeviceSmartTouchInfo.onClick {
            DeviceManager.isConnect().yes {
                var switch = mViewBind.etDeviceSmartTouchSwitch.text.toString()
                var mode = mViewBind.etDeviceSmartTouchMode.text.toString()
                (switch.isNullOrEmpty() || mode.isNullOrEmpty()).yes {
                    ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                }.otherwise {
                    ServiceSdkCommandV2.setDeviceSmartTouchMode(DeviceSmartTouchMode().copy(
                        touchAppSwitch = switch.toInt(),
                        touchMode = mode.toInt()
                    ), object : BCallback {
                        override fun result(r: Boolean) {
                            r.yes {
                                ToastUtils.showLong("${R.string.str_common_set_successful.resString()}")
                            }
                        }

                    })
                }
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }
        //*****设备触控功能工作/休眠状态演示*****
        mViewBind.btGetTouchWorkstate.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getTouchWorkState(object : ICallback {
                    override fun result(r: Int) {
                        mViewBind.txCurrentTouchWorkstate.text = "${R.string.device_touch_work_state_current.resString()}" + r.toString()
                    }
                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }
        mViewBind.btSetTouchWorkState.onClick {
            var state = mViewBind.etTouchWorkState.text.toString()
            (state.isNullOrEmpty()).yes {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
            }.otherwise {
                ServiceSdkCommandV2.setTouchWorkState(state.toInt(), object : ICallback {
                    override fun result(r: Int) {
                        if (r == 1) {
                            ToastUtils.showLong("${R.string.str_common_set_successful.resString()}")
                        } else {
                            ToastUtils.showLong("${R.string.str_common_set_fail.resString()}")
                        }
                    }
                })
            }
        }
        //*****设备SOS功能开关设置演示*****
        mViewBind.btGetSosState.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getSosState(object : ICallback {
                    override fun result(r: Int) {
                        mViewBind.txCurrentSosState.text = "${R.string.device_sos_state_current.resString()}" + r.toString()
                    }
                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }
        mViewBind.btSetSosState.onClick {
            var state = mViewBind.etSosState.text.toString()
            (state.isNullOrEmpty()).yes {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
            }.otherwise {
                ServiceSdkCommandV2.setSosState(state.toInt(), object : ICallback {
                    override fun result(r: Int) {
                        if (r == 1) {
                            ToastUtils.showLong("${R.string.str_common_set_successful.resString()}")
                        } else {
                            ToastUtils.showLong("${R.string.str_common_set_fail.resString()}")
                        }
                    }
                })
            }
        }

        //*****设备解绑/重启/复位接口演示*****
        mViewBind.btSetDeviceState.onClick {
            var type = mViewBind.etDeviceState.text.toString()
            (type.isNullOrEmpty()).yes {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
            }.otherwise {
                DeviceManager.isConnect().yes {
                    ServiceSdkCommandV2.handleDevice(type.toInt(), object : BCallback {
                        override fun result(r: Boolean) {
                            ToastUtils.showLong("${R.string.str_common_set_successful.resString()}")
                        }

                    })
                }.otherwise {
                    ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
                }
            }
        }

        mViewBind.btHealthData.click {
            mViewBind.btHealthData.isEnabled = false
            healthDataAda.data.isNotEmpty().yes {
                healthDataAda.data.clear()
                healthDataAda.notifyDataSetChanged()
            }
            var year = mViewBind.etHealthYear.text?.trim().toString()
            var month = mViewBind.etHealthMonth.text?.trim().toString()
            var day = mViewBind.etHealthDay.text?.trim().toString()
            if (year.isNullOrEmpty() || month.isNullOrEmpty() || day.isNullOrEmpty()) {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                mViewBind.btHealthData.isEnabled = true
            } else {
                var time = TimeUtil.yearMonthDay(year.toInt(), month.toInt(), day.toInt())
                "时间:${time},convert:${TimeUtil.getTimeStr2(time)}".logIx()
                DeviceManager.isConnect().yes {
                    (time <= 0).yes {
                        ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                        mViewBind.btHealthData.isEnabled = true
                    }.otherwise {
                        ServiceSdkCommandV2.getHealthData(
                            time,
                            object : HealthDataCallback {
                                override fun onFinish(time: Long) {
                                    ToastUtils.showLong("数据结束 时间:${TimeUtil.getTimeStr2(time)}")
                                    mViewBind.btHealthData.isEnabled = true
                                }

                                override fun onReceive(data: MutableList<HealthDataBean>) {
                                    healthDataAda.addData(data.toFastJson())
                                    "健康数据每一包获取到的大小：data:${data.size},data.toFastJson():${data.toFastJson()}".logIx()
                                }

                            })
                    }
                }
            }
        }

//        mViewBind.btSleepDetail.click {
//            mViewBind.btSleepDetail.isEnabled = false
//            sleepDetailAda.data.isNotEmpty().yes {
//                sleepDetailAda.data.clear()
//                sleepDetailAda.notifyDataSetChanged()
//            }
//            var year = mViewBind.etSleepDetailYear.text?.trim().toString()
//            var month = mViewBind.etSleepDetailMonth.text?.trim().toString()
//            var day = mViewBind.etSleepDetailDay.text?.trim().toString()
//            if (year.isNullOrEmpty() || month.isNullOrEmpty() || day.isNullOrEmpty()) {
//                ToastUtils.showLong("时间不能为空！")
//            } else {
//                var time = TimeUtil.yearMonthDay(year.toInt(), month.toInt(), day.toInt())
//                "时间:${time},convert:${TimeUtil.getTimeStr2(time)}".logIx()
//                ServiceSdkCommandV2.getSleepDetail(time,
//                    object : SleepDetailCallback {
//                        override fun onFinish(timeInMills: Long) {
//                            "睡眠结束".logIx()
//                            ToastUtils.showLong("数据结束 时间:${TimeUtil.getTimeStr2(timeInMills)}")
//                            mViewBind.btSleepDetail.isEnabled = true
//                        }
//
//                        override fun onReceive(data: SleepDetail) {
//
//                            sleepDetailAda.addData(data.toFastJson())
//
//                            "demo收到睡眠数据：data:${data.toFastJson()}".logIx()
//                        }
//                    })
//            }
//        }
//        mViewBind.btSleepTotal.click {
//            mViewBind.btSleepTotal.isEnabled = false
//            sleepTotalAda.data.isNotEmpty().yes {
//                sleepTotalAda.data.clear()
//                sleepTotalAda.notifyDataSetChanged()
//            }
//
//            var year = mViewBind.etSleepTotalYear.text?.trim().toString()
//            var month = mViewBind.etSleepTotalMonth.text?.trim().toString()
//            var day = mViewBind.etSleepTotalDay.text?.trim().toString()
//            if (year.isNullOrEmpty() || month.isNullOrEmpty() || day.isNullOrEmpty()) {
//                ToastUtils.showLong("时间不能为空！")
//            } else {
//                var time = TimeUtil.yearMonthDay(year.toInt(), month.toInt(), day.toInt())
//                "时间:${time},convert:${TimeUtil.getTimeStr2(time)}".logIx()
//                ServiceSdkCommandV2.getSleepTotal(time,
//                    object : SleepTotalCallback {
//                        override fun onFinish(timeInMills: Long) {
//                            "睡眠综合结束".logIx()
//                            mViewBind.btSleepTotal.isEnabled = true
//                        }
//
//                        override fun onReceive(data: SleepTotal) {
//                            sleepTotalAda.addData(data.toFastJson())
//                            "demo收到睡眠综合数据：data:${data.toFastJson()}".logIx()
//                        }
//                    })
//            }
//        }


        /**
         * Added by yezhihua
         */
        mViewBind.btSleepData.click {
            mViewBind.btSleepData.isEnabled = false
            sleepDataAda.data.isNotEmpty().yes {
                sleepDataAda.data.clear()
                sleepDataAda.notifyDataSetChanged()
            }

            var year = mViewBind.etSleepDataYear.text?.trim().toString()
            var month = mViewBind.etSleepDataMonth.text?.trim().toString()
            var day = mViewBind.etSleepDataDay.text?.trim().toString()
            if (year.isNullOrEmpty() || month.isNullOrEmpty() || day.isNullOrEmpty()) {
                ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                mViewBind.btSleepData.isEnabled = true
            } else {
                var time = TimeUtil.yearMonthDay(year.toInt(), month.toInt(), day.toInt())
                "时间:${time}, convert:${TimeUtil.getTimeStr2(time)}".logIx()
                ServiceSdkCommandV2.getSleepData(
                    time,
                    object : SleepDataCallback {
                        override fun sleepData(bean: MutableList<SleepRecord>) {
                            "收到睡眠数据数量：${bean.size}".logIx()
                            sleepDataAda.addData(bean.toFastJson())
                            "收到睡眠数据：${bean.toFastJson()}".logIx()
                            mViewBind.btSleepData.isEnabled = true
                        }
                    }
                )
            }
        }

        mViewBind.btDeviceSmartTouchVideo.click {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getDeviceSmartTouchMode(object : DeviceSmartTouchModeCallback {
                    override fun deviceSmartTouch(bean: DeviceSmartTouchMode) {
                        ("触控应用开关状态" + bean.touchAppSwitch + ";" + "mode" + bean.touchMode).logIx()
                        if (bean.touchMode == 0x03 && bean.touchAppSwitch == 1) {
                            ServiceSdkCommandV2.getTouchVideoOpMode(object : ICallback {
                                override fun result(r: Int) {
                                    mViewBind.txDeviceSmartTouchInfo.text = "获取到的模式为:$r"
                                    "获取到的模式为：r:$r".logIx()
                                }

                            })

                        } else {
                            ToastUtils.showLong("${R.string.str_device_video_touch_mode_param_error.resString()}")
                        }
                    }

                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }

        mViewBind.btSetDeviceSmartTouchVideo.click {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getDeviceSmartTouchMode(object : DeviceSmartTouchModeCallback {
                    override fun deviceSmartTouch(bean: DeviceSmartTouchMode) {
                        ("触控应用开关状态" + bean.touchAppSwitch + ";" + "mode" + bean.touchMode).logIx()
                        if (bean.touchMode == 0x03 && bean.touchAppSwitch == 1) {
                            var mode = mViewBind.etDeviceSmartTouchVideo.text.trim().toString()
                            ServiceSdkCommandV2.sendTouchVideoOpMode(mode.toInt(),
                                object : ICallback {
                                    override fun result(r: Int) {
                                        (r == 1).yes { ToastUtils.showLong("${R.string.str_common_set_successful.resString()}") }
                                    }
                                })

                        } else {
                            ToastUtils.showLong("${R.string.str_device_video_touch_mode_param_error.resString()}")
                        }
                    }

                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }


        //开始运动前需要获取当前运动状态
        mViewBind.btStartSport.isEnabled = false
        mViewBind.btPauseSport.isEnabled = false
        mViewBind.btResumeSport.isEnabled = false
        mViewBind.btStopSport.isEnabled = false

        //开始前先获取获取运动是否可以开始运动
        mViewBind.btHaveSport.click {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getSportStatus(object : SportStatusCallback {
                    override fun status(bean: SportStatus) {
                        when (bean.sportStatus) {
                            0 -> {
                                ToastUtils.showLong("${R.string.str_device_start_new_sport.resString()}")
                                "无正在进行的运动，立即开始运动".logBx()
                                mViewBind.btStartSport.isEnabled = true
                            }
                            1, 2 -> {
                                sportType = bean.sportType
                                sportId = bean.sportId
                                ToastUtils.showLong("${R.string.str_device_stop_current_sport.resString()}")
                                "有正在进行的运动，先结束当前运动".logBx()
                                AlertDialog.Builder(this@MainActX).apply {
                                    setTitle("${R.string.str_common_tip.resString()}")
                                    setMessage("${R.string.str_device_stop_current_sport.resString()}")
                                    setPositiveButton("${R.string.str_device_stop_sport.resString()}") { _, _ ->
                                        // 确定按钮的回调逻辑
                                        //有正在进行的运动，需要先停止当前运动然后获取运动综合数据
                                        ServiceSdkCommandV2.sportStopAndGetRecord(
                                            bean.sportId,
                                            bean.sportType,
                                            object : ICallback {
                                                override fun result(r: Int) {
                                                    if (r == 1) {
                                                        if (sportTimer.state == IntervalStatus.STATE_ACTIVE) {
                                                            sportTimer.stop(true)
                                                        }
                                                    } else {
                                                        ToastUtils.showLong("${R.string.str_device_stop_sport_fail.resString()}")
                                                    }
                                                }
                                            },
                                            object : SportRecordCallback {
                                                override fun onSportRecord(record: SportRecord?) {
                                                    record?.let { showSportRecordUI(it) }
                                                }
                                            }
                                        )
                                    }
                                    setNegativeButton("${R.string.str_common_cancel.resString()}") { _, _ ->
                                        // 取消按钮的回调逻辑
                                    }
                                }.create().show()
                            }
                            3 -> {
                                sportType = bean.sportType
                                sportId = bean.sportId
                                ToastUtils.showLong("${R.string.str_device_having_sport_data.resString()}")
                                "有运动数据需要上传，先获取运动数据".logBx()
                                ServiceSdkCommandV2.getSportRecord(object : SportRecordCallback {
                                    override fun onSportRecord(record: SportRecord?) {
                                        record?.let { showSportRecordUI(it) }
                                    }
                                })
                            }
                            else -> {
                                ToastUtils.showLong("${R.string.str_device_start_new_sport.resString()}")
                                "无正在进行的运动，立即开始运动".logBx()
                                mViewBind.btStartSport.isEnabled = true
                            }
                        }
                    }
                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }

        //开始运动
        mViewBind.btStartSport.click {
            DeviceManager.isConnect().yes {
                mViewBind.btStartSport.isEnabled = false
                sportId = System.currentTimeMillis()
                sportType = sportList[sportTypePosition]
                ServiceSdkCommandV2.sportStart(sportType, sportId, object : ICallback {
                    override fun result(r: Int) {
                        if (r == 1) {
                            //运动成功开始，启动计时器没5秒查询一次实时数据
                            isPause = false
                            mViewBind.btPauseSport.isEnabled = true
                            mViewBind.btStopSport.isEnabled = true
                            startTimer()
                        } else {
                            ToastUtils.showLong("${R.string.str_device_start_new_sport_fail.resString()}")
                            mViewBind.btStartSport.isEnabled = true
                        }
                    }
                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }

        //暂停运动
        mViewBind.btPauseSport.click {

            mViewBind.btPauseSport.isEnabled = false
            ServiceSdkCommandV2.sportPause(sportType, sportId, object : ICallback {
                override fun result(r: Int) {
                    if (r == 1) {
                        "运动暂停，暂停运动计时。".logBx()
                        mViewBind.btResumeSport.isEnabled = true
                        isPause = true
                        sportTimer.pause()
                        pauseStartTime = System.currentTimeMillis()
                    } else if (r == 3) {
                        "设备已停止运动，停止运动计时，结束运动。".logBx()
                        ToastUtils.showLong("${R.string.str_device_stopped_sport.resString()}")
                        ServiceSdkCommandV2.getSportRecord(object : SportRecordCallback {
                            override fun onSportRecord(record: SportRecord?) {
                                record?.let { showSportRecordUI(it) }
                            }
                        })
                        sportTimer.stop(true)
                    } else {
                        ToastUtils.showLong("${R.string.str_device_pause_new_sport_fail.resString()}")
                        mViewBind.btPauseSport.isEnabled = true
                    }
                }
            })
        }

        //继续/恢复运动
        mViewBind.btResumeSport.click {
            mViewBind.btResumeSport.isEnabled = false
            ServiceSdkCommandV2.sportResume(sportType, sportId, object : ICallback {
                override fun result(r: Int) {
                    if (r == 1) {
                        "运动恢复，继续运动计时。".logBx()
                        mViewBind.btPauseSport.isEnabled = true
                        isPause = false
                        pauseTime += (System.currentTimeMillis() - pauseStartTime) / 1000
                        sportTimer.resume()
                    } else if (r == 3) {
                        "设备已停止运动，停止运动计时，结束运动。".logBx()
                        ToastUtils.showLong("${R.string.str_device_stopped_sport.resString()}")
                        ServiceSdkCommandV2.getSportRecord(object : SportRecordCallback {
                            override fun onSportRecord(record: SportRecord?) {
                                record?.let { showSportRecordUI(it) }
                            }
                        })
                        sportTimer.stop(true)
                    } else {
                        ToastUtils.showLong("${R.string.str_device_resume_new_sport_fail.resString()}")
                        mViewBind.btResumeSport.isEnabled = true
                    }
                }
            })
        }

        //停止运动
        mViewBind.btStopSport.click {
            "运动结束，通知蓝牙设备结束运动并上传数据。".logIx()

            ServiceSdkCommandV2.sportStop(sportType, sportId, object : ICallback {
                override fun result(r: Int) {
                    if (r == 1) {
                        isPause = true
                        mViewBind.btStartSport.isEnabled = false
                        mViewBind.btPauseSport.isEnabled = false
                        mViewBind.btResumeSport.isEnabled = false
                        mViewBind.btStopSport.isEnabled = false
                    } else {
                        ToastUtils.showLong("${R.string.str_device_stop_sport_fail.resString()}")
                    }
                }
            }, object : SportRecordCallback {
                override fun onSportRecord(record: SportRecord?) {
                    record?.let { showSportRecordUI(it) }
                }
            })
            sportTimer.stop(true)
        }


        mViewBind.btCalcuScores.click {

            /**
             * 健康评分算法需要当天的健康步数、运动和睡眠。
             * 还需要之前7天（如果有）的健康步数数据、睡眠数据、运动记录和健康评分等数据，如果部分数据没有，则有的评分指标计算结果为0
             */

            var healthData : MutableList<HealthRecord> = mutableListOf()
            var sleepData : MutableList<SleepRecord> = mutableListOf()
            var sportData : MutableList<SportRecord> = mutableListOf()
            var healthScore : MutableList<HealthScore> = mutableListOf()

            //添加睡眠数据，需要昨晚以及之前7个晚上的睡眠数据，正式调用需从数据库中查询数据
            sleepData = addSleepData()

            //添加健康数据，需要今天以及之前7天的健康数据，正式调用需从数据库中查询数据
            healthData = addHealthData()

            //添加运动数据，需要今天以及之前7天的所有运动记录，正式调用需从数据库中查询数据
            sportData = addSportData()

            //添加健康评分数据，需要之前7天的健康得分，正式调用需从数据库中查询数据
            healthScore = addHealthScore()

            //调用算法，date为当天的0点0分0秒的时间戳
            ServiceSdkCommandV2.calcuHealthAssessment(
                1717689600000,
                sleepData,
                healthData,
                sportData,
                healthScore,
                object: HealthScoreCallback {
                    override fun healthScore(bean: HealthScore) {
                        healthScoreAda.addData(bean.toFastJson())
                    }
                }
            )
        }

        mViewBind.btReason.click {
            ServiceSdkCommandV2.fetchReason()
        }
        //Get用户信息
        mViewBind.btnGetUserinfo.click {
            DeviceManager.isConnect()?.yes {
                ServiceSdkCommandV2.getUserInfo(object : UserInfoCallback {
                    override fun userInfo(bean: UserInfo) {
                        mViewBind.etSex.setText(if (bean.gender == 1) "男" else "女")
                        mViewBind.etAge.setText(bean.birth)
                        mViewBind.etHeight.setText("${bean.height}")
                        mViewBind.etWeight.setText("${bean.weight}")
                    }
                })
            }?.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }

        }
        //Set用户信息
        mViewBind.btnSetUserinfo.click {
            DeviceManager.isConnect()?.yes {
                if (mViewBind.etSex.text.toString().isEmpty()
                    || mViewBind.etAge.text.toString().isEmpty()
                    || mViewBind.etHeight.text.toString().isEmpty()
                    || mViewBind.etWeight.text.toString().isEmpty()
                ) {
                    ToastUtils.showLong("${R.string.str_common_param_error.resString()}")
                    return@click
                }
                try {
                    val userInfo = UserInfo(
                        mViewBind.etSex.text.toString().toInt(),
                        mViewBind.etAge.text.toString(),
                        mViewBind.etHeight.text.toString().toInt(),
                        mViewBind.etWeight.text.toString().toFloat()
                    )

                    ServiceSdkCommandV2.setUserInfo(userInfo, object : BCallback {
                        override fun result(r: Boolean) {
                            r.logIx("设置用户信息")
                            ToastUtils.showLong(if (r) "${R.string.str_common_set_successful.resString()}" else "${R.string.str_common_set_fail.resString()}")
                        }
                    })
                } catch (e: Exception) {
                    ToastUtils.showLong(e.message)
                }
            }?.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }
        }

        mViewBind.etSex.addTextChangedListener {

        }
        mViewBind.etAge.addTextChangedListener {

        }
        mViewBind.etHeight.addTextChangedListener {

        }
        mViewBind.etWeight.addTextChangedListener {

        }


        mViewBind.ecgInitialize.click {
            DeviceManager.isConnect().yes {
                mViewBind.ecgInitialize.isEnabled = false
                mViewBind.ecgInitialize.text = "${R.string.str_device_ecg_initializing.resString()}"
                ServiceSdkCommandV2.initializeECGDevice(object : BCallback {
                    override fun result(r: Boolean) {
                        "ecgInitialize:result:$r".logIx()
                        ToastUtils.showLong("${R.string.str_device_ecg_initial_result.resString()} $r")
                        r.yes {
                            mViewBind.ecgStart.isEnabled = true
                            mViewBind.ecgInitialize.isEnabled = false
                            mViewBind.ecgInitialize.text = "${R.string.str_device_ecg_init.resString()}"
                            mViewBind.ecgChart.initialize()
                            startECGMeasureCountDownJob()
                        }.otherwise {
                            mViewBind.ecgInitialize.isEnabled = true
                            mViewBind.ecgInitialize.text = "${R.string.str_device_ecg_init.resString()}"
                        }
                    }
                })
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }

        }
        mViewBind.ecgStart.click {
            measureECGCountDownJob?.cancel()
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.startECGMeasurement(object : ECGCallback {
                    override fun onError(errorType: Int) {
                        "ECG Callback onError:$errorType".logIx()
                    }

                    override fun onFinish(list: MutableList<ECGData>, avgHr: Float, type: Int) {
                        "ECG Callback onFinish:${list.size},avgHr:$avgHr,type:$type".logIx()
                        ToastUtils.showLong("${R.string.str_device_measure_stop.resString()}")
                        try {
                            mViewBind.ecgInitialize.isEnabled = true
                            mViewBind.ecgStart.isEnabled = false
                            mViewBind.ecgStop.isEnabled = false
                        } catch (e: Exception) {
                            "startECGMeasurement onFinish catch Exception:$e".logIx()
                        }
                    }

                    override fun onReceive(data: MutableList<ECGData>) {
                        "ECG Callback onReceive 数据包大小:${data.size}".logIx()
                        try {
                            data.forEach {
                                mViewBind.ecgChart.setData(it.ecgValue)
                            }
                        } catch (e: Exception) {
                            "startECGMeasurement onReceive catch Exception:$e".logIx()
                        }
                    }

                    override fun onStart() {
                        "ECG Callback onStart".logIx()
                        mViewBind.ecgInitialize.isEnabled = false
                        mViewBind.ecgStart.isEnabled = false
                        mViewBind.ecgStop.isEnabled = true
                    }
                }, true)
            }.otherwise {
                ToastUtils.showLong("${R.string.str_device_connect_tip.resString()}")
            }

        }
        mViewBind.ecgStop.click {
            mViewBind.ecgInitialize.isEnabled = true
            mViewBind.ecgInitialize.text = "${R.string.str_device_ecg_init.resString()}"
            mViewBind.ecgStart.isEnabled = false
            mViewBind.ecgStop.isEnabled = false
            ServiceSdkCommandV2.stopECGMeasurement {
            }

        }

        mViewBind.btnGluCreateUser.isEnabled = !gluRegister
        mViewBind.btnGluCreateUser.onClick {
            //先检查该设备是否已授权血糖功能
            var deviceGluAuthority = false
            ServiceSdkCommandV2.checkDeviceGluAuthority(
                object : BCallback {
                    override fun result(r: Boolean) {
                        deviceGluAuthority = r
                        "Device GLU function authority state = ${r}".logIx()
                        if (!deviceGluAuthority) {
                            ToastUtils.showLong("设备未授权")
                            return
                        }

                        if (mViewBind.etGluAge.text.toString().isEmpty() ||
                            mViewBind.etGluGender.text.toString().isEmpty() ||
                            mViewBind.etGluHeight.text.toString().isEmpty() ||
                            mViewBind.etGluWeight.text.toString().isEmpty() ||
                            mViewBind.etGluHasDiabetesFamilyHistory.text.toString().isEmpty()
                        ) {
                            ToastUtils.showLong("请先完整填写完用户信息")
                            return
                        }
                        ServiceSdkCommandV2.createBloodGluUser(
                            mViewBind.etGluAge.text.toString().toInt(),
                            mViewBind.etGluGender.text.toString().toInt(),
                            mViewBind.etGluHeight.text.toString().toInt(),
                            mViewBind.etGluWeight.text.toString().toFloat(),
                            mViewBind.etGluHasDiabetesFamilyHistory.text.toString().toInt() == 1,
                            object : BCallback {
                                override fun result(r: Boolean) {
                                    if (r) {
                                        ToastUtils.showLong("创建成功")
                                    } else
                                        ToastUtils.showLong("创建失败")
                                    gluRegister = r
                                    mViewBind.btnGluCreateUser.isEnabled = !gluRegister
                                }
                            }
                        )
                    }
                }
            )
        }
        mViewBind.btnGluUpdateUser.onClick {
            if (mViewBind.etGluAge.text.toString().isEmpty() ||
                mViewBind.etGluGender.text.toString().isEmpty() ||
                mViewBind.etGluHeight.text.toString().isEmpty() ||
                mViewBind.etGluWeight.text.toString().isEmpty() ||
                mViewBind.etGluHasDiabetesFamilyHistory.text.toString().isEmpty()
            ) {
                ToastUtils.showLong("请先完整填写完用户信息")
                return@onClick
            }
            ServiceSdkCommandV2.updateBloodGluUser(
                mViewBind.etGluAge.text.toString().toInt(),
                mViewBind.etGluGender.text.toString().toInt(),
                mViewBind.etGluHeight.text.toString().toInt(),
                mViewBind.etGluWeight.text.toString().toFloat(),
                mViewBind.etGluHasDiabetesFamilyHistory.text.toString().toInt() == 1,
                object : BCallback {
                    override fun result(r: Boolean) {
                        if (r)
                            ToastUtils.showLong("更新成功")
                        else
                            ToastUtils.showLong("更新失败")
                    }
                }
            )
        }
        mViewBind.btnGluMeasureStart.onClick {
            DeviceManager.isConnect().yes {
                //先检查该设备是否已授权血糖功能
                var deviceGluAuthority = false
                ServiceSdkCommandV2.checkDeviceGluAuthority(
                    object : BCallback {
                        override fun result(r: Boolean) {
                            deviceGluAuthority = r
                            "Device GLU function authority state = ${r}".logIx()

                            if (!deviceGluAuthority) {
                                ToastUtils.showLong("设备未授权")
                                return
                            }
                            mViewBind.btnGluMeasureStart.isEnabled = false
                            ServiceSdkCommandV2.startBloodGluMeasure(
                                true,
                                false,
                                object : BloodGluMeasureCallback {

                                    override fun onStartSuccess() {
                                        mViewBind.tvGluMeasureStatus.text = "开始测量血糖成功"
                                    }

                                    override fun onStartFail() {
                                        mViewBind.tvGluMeasureStatus.text = "开始测量血糖失败"
                                        mViewBind.btnGluMeasureStart.isEnabled = true
                                    }

                                    override fun onMeasureProgress(progress: Int) {
                                        mViewBind.tvGluMeasureStatus.text = "测量中...   进度$progress"
                                    }

                                    override fun onProcessData() {
                                        mViewBind.btnGluMeasureStart.isEnabled = true
                                        mViewBind.tvGluMeasureStatus.text = "ppg数据收完了,正在等待计算结果....."
                                    }

                                    override fun onProcessSuccess(result: RiskLevel) {
                                        mViewBind.tvGluMeasureStatus.text = "结果计算成功 $result"
                                    }

                                    override fun onProcessFail(e: Exception) {
                                        "onProcessFail-----------------  $e".logIx()
                                        mViewBind.tvGluMeasureStatus.text = "结果计算失败"
                                    }
                                }
                            )
                        }
                    }
                )
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }
        mViewBind.btnGluMeasureStop.click {

            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.stopBloodGluMeasure(object : BCallback {
                    override fun result(r: Boolean) {
                        if (r) {
                            mViewBind.tvGluMeasureStatus.text = "取消测量成功"
                            ToastUtils.showLong("取消测量成功")
                            mViewBind.btnGluMeasureStart.isEnabled = true
                        } else {
                            mViewBind.tvGluMeasureStatus.text = "取消测量失败"
                            ToastUtils.showLong("取消测量失败")
                        }
                    }
                })
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }

        mViewBind.layoutVibrate.btnGetVibrate.checkConnectClick {
            val type = mViewBind.layoutVibrate.etVibrateType.text.toString()
            val typeValue = if (type.isEmpty()) 0 else type.toInt()

            mViewBind.layoutVibrate.tvDataVibrate.text = "loading..."
            ServiceSdkCommandV2.getVibrateToggle(typeValue) { list ->
                if (list.isNotEmpty()) {
                    mViewBind.layoutVibrate.tvDataVibrate.text = list.toFastJson()
                    list.forEach {
                        when (it.type) {
                            1 -> mViewBind.layoutVibrate.cbRingVibrationSys.isChecked =
                                (it.status == 1)

                            4 -> mViewBind.layoutVibrate.cbRingVibrationCall.isChecked =
                                (it.status == 1)
                        }
                    }
                }
            }
        }

        mViewBind.layoutVibrate.btnSetVibrate.checkConnectClick {
            val vibrateToggleList = mutableListOf<VibrateToggleModel>()
            vibrateToggleList.add(VibrateToggleModel(1, if (mViewBind.layoutVibrate.cbRingVibrationSys.isChecked) 1 else 0))
            vibrateToggleList.add(VibrateToggleModel(4, if (mViewBind.layoutVibrate.cbRingVibrationCall.isChecked) 1 else 0))
            ServiceSdkCommandV2.setVibrateToggle(vibrateToggleList, object : BCallback {
                override fun result(r: Boolean) {
                    ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                }
            })
        }

        mViewBind.layoutVibrate.btnRequestPhoneState.checkConnectClick {
            if (!hasCallPermission(this@MainActX)) {
                phoneStateRequest.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            } else {
                CallManager.checkAndRegisterCallListener(this@MainActX)
            }
        }

        mViewBind.layoutVibrate.btnVibrateWarningSet.checkConnectClick {
            try {
                val values = mViewBind.layoutVibrate.etVibrateWaining.text.toString().split(",").map { it.toInt() }.toList()
                if (values.size != 5) {
                    ToastUtils.showLong(R.string.str_common_param_error)
                    return@checkConnectClick
                }
                ServiceSdkCommandV2.setVibrateWarning(
                    VibrateWainingModel(values[0], values[1], values[2], values[3], values[4]),
                    object : BCallback {
                        override fun result(r: Boolean) {
                            ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                        }
                    })
            } catch (e: Exception) {
                ToastUtils.showLong(R.string.str_common_param_error)
            }
        }

        mViewBind.layoutVibrate.btnVibrateWarningGet.checkConnectClick {
            mViewBind.layoutVibrate.tvVibrateWainingPack.text = "loading..."
            val inputType = mViewBind.layoutVibrate.etGetVibrateType.text.toString()
            val type = if (inputType.isEmpty()) 1 else inputType.toInt()
            ServiceSdkCommandV2.getVibrateWaining(type) { list ->
                mViewBind.layoutVibrate.tvVibrateWainingPack.text = list.toMoshiString()
            }
        }


        mViewBind.layoutVibrate.btnVibrateSetAlarm.checkConnectClick {
            val alarmModel = AlarmModel(
                id = 1,
                duration = 5,
                repeats = charArrayOf('1', '1', '0', '1', '1', '0', '1'),
                time = 1227,
                isOpen = 1,
                name = "默认闹钟名",
                opType = 1,
            )
            try {
                val values = mViewBind.layoutVibrate.etVibrateSetAlarm.text.toString().split(",").toList()
                val repeatsValues = mViewBind.layoutVibrate.etVibrateSetAlarmRepeat.text.toString().split(",").map { it.toInt() }.toList()
                alarmModel.id = values[0].toInt()
                alarmModel.duration = values[1].toInt()
                alarmModel.repeats = repeatsValues.map { it1 -> it1.toChar() }.toCharArray()
                alarmModel.time = values[2].toLong()
                alarmModel.isOpen = values[3].toInt()
                alarmModel.name = values[4]
                alarmModel.opType = values[5].toInt()

                ServiceSdkCommandV2.setVibrateAlarm(alarmModel, object : BCallback {
                    override fun result(r: Boolean) {
                        ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                    }
                })
            } catch (e: Exception) {
                ToastUtils.showLong(R.string.str_common_param_error)
            }
        }

        mViewBind.layoutVibrate.btnVibrateGetAlarm.checkConnectClick {
            ServiceSdkCommandV2.getVibrateAlarm { list ->
                mViewBind.layoutVibrate.tvAllAlarm.text = list.toMoshiString()
            }
        }


        //客户定制震动模式参数设置
        mViewBind.btnSetVibrateMode.onClick {
            if (mViewBind.etVibrateMode.text.toString().isEmpty() ||
                mViewBind.etVibrateTimes.text.toString().isEmpty() ||
                mViewBind.etVibrateDuration.text.toString().isEmpty() ||
                mViewBind.etVibrateInterval.text.toString().isEmpty()
            ) {
                ToastUtils.showLong(R.string.str_common_parameter_not_all_filled)
                return@onClick
            }
            val vibrateMode = VibrationModeBean().apply {
                this.modeNo = mViewBind.etVibrateMode.text.toString().toInt()
                this.times = mViewBind.etVibrateTimes.text.toString().toInt()
                this.duration = mViewBind.etVibrateDuration.text.toString().toInt()
                this.interval = mViewBind.etVibrateInterval.text.toString().toInt()
            }

            ServiceSdkCommandV2.setVibrationMode(
                vibrateMode,
                object : ICallback {
                    override fun result(r: Int) {
                        if (r == 1) ToastUtils.showLong(R.string.str_common_set_successful) else ToastUtils.showLong(R.string.str_common_set_fail)
                    }
                }
            )
        }
        //客户定制震动模式参数查询
        mViewBind.btnGetVibrateMode.onClick {
            DeviceManager.isConnect().yes {
                ServiceSdkCommandV2.getVibrationMode(0.toInt(), object : VibrationModeCallback {
                    override fun result(vibrationModes: List<VibrationModeBean>?) {
                        if (vibrationModes != null) {
                            mViewBind.txVibrationModes.text = vibrationModes.toJsonString()
                        } else {
                            mViewBind.txVibrationModes.text = "No result."
                        }
                    }
                })
            }.otherwise {
                ToastUtils.showLong(R.string.str_device_connect_tip.resString())
            }
        }


        val catchEmotionData = mutableListOf<EmotionModel>()
        mViewBind.layoutEmotion.btnEmotionGet.checkConnectClick {
            val stringBuffer = StringBuffer() // 初始化 StringBuffer
            ServiceSdkCommandV2.getEmotion(
                System.currentTimeMillis(),
                if (catchEmotionData.isNotEmpty()) catchEmotionData.last().datetime else 0L
            ) {
                catchEmotionData.addAll(it)
                it.forEach { it1 ->
                    val stressLevel = MoodAlgorithm.stress2StressLevel(it1.stress)
                    val valenceLevel = MoodAlgorithm.valence2ValenceLevel(it1.valence)
                    val emotionLevel = it1.emotionLevel
                    val rpeLevel = MoodAlgorithm.fatigue2FatigueLevel(it1.rpe)
                    stringBuffer.append("${getString(R.string.emotion_stress_level)}: $stressLevel\n")
                    stringBuffer.append("${getString(R.string.emotion_valence_level)}: $valenceLevel\n")
                    stringBuffer.append("${getString(R.string.emotion_emotion_level)}: $emotionLevel\n")
                    stringBuffer.append("${getString(R.string.emotion_fatigue_level)}: $rpeLevel\n")
                    stringBuffer.append("\n")
                }

                mViewBind.layoutEmotion.tvEmotionInfo.text = it.toMoshiString()
            }
        }

        mViewBind.layoutEmotion.btnIntervalSet.checkConnectClick {
            val interval = mViewBind.layoutEmotion.etInterval.text.toString()
            ServiceSdkCommandV2.setEmotionMeasureInterval(interval.toInt(), object :
                BCallback {
                override fun result(r: Boolean) {
                    ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                }
            })
        }

        mViewBind.layoutEmotion.btnIntervalGet.checkConnectClick {
            ServiceSdkCommandV2.getEmotionMeasureInterval(object :
                ICallback {
                override fun result(r: Int) {
                    mViewBind.layoutEmotion.tvEmotionInfo.text = "emotion measure interval = $r"
                }
            })
        }

        mViewBind.layoutStep.btnStepGet.checkConnectClick {
            ServiceSdkCommandV2.getStepData { step, kcal, distance ->
                mViewBind.layoutStep.tvStepData.text = "step: $step,  kcal:$kcal, distance: $distance"
            }
        }

        mViewBind.layoutDeviceSet.btnReset.checkConnectClick {
            ServiceSdkCommandV2.deviceSet(1, object : BCallback {
                override fun result(r: Boolean) {
                    ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                }
            })
        }

        mViewBind.layoutDeviceSet.btnUnbind.checkConnectClick {
            ServiceSdkCommandV2.deviceSet(2, object : BCallback {
                override fun result(r: Boolean) {
                    ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                }
            })
        }

        mViewBind.layoutDeviceSet.btnReboot.checkConnectClick {
            ServiceSdkCommandV2.deviceSet(4, object : BCallback {
                override fun result(r: Boolean) {
                    ToastUtils.showLong(if (r) R.string.str_common_set_successful else R.string.str_common_set_fail)
                }
            })
        }
    }

    private fun connectUI() {
        mViewBind.connect.text = "${R.string.str_device_disconnect.resString()}"
        mViewBind.connectState.text = "${R.string.str_device_already_connect.resString()}"
        mViewBind.connect.isEnabled = true
    }

    private fun disconnectUI() {
        mViewBind.connect.text = "${R.string.str_device_connect.resString()}"
        mViewBind.connectState.text = "${R.string.str_device_already_disconnect.resString()}"
        mViewBind.txBatteryInfo.text = ""
        mViewBind.connect.isEnabled = true
        mViewBind.txDeviceInfo.text = ""

        if (sportTimer.state == IntervalStatus.STATE_ACTIVE) {
            sportTimer.stop(true)
        }
    }

    private fun errorUI(throwable: Throwable, state: Int) {
        mViewBind.connect.isEnabled = true
        mViewBind.connect.text = "${R.string.str_device_connect.resString()}"
        mViewBind.connectState.text = "${R.string.str_device_connect_fail.resString()}, state：${state}, ${throwable.message}"
        mViewBind.connect.isEnabled = true
    }

    /**
     * 定时器监听器
     **/
    private fun startTimer() {
        isPause = false
        useTime = 0L
        km = 0.0
        kcal = 0.0
        realStep = 0
        pauseTime = 0
        sportRecordAda.setList(emptyList())
        mViewBind.tvSportDataType.text = "${R.string.str_device_realtime_sports_data.resString()}"
        sportStartTime = System.currentTimeMillis()

        //这里处理UI
        sportTimer.subscribe {
            mViewBind.tvUsedTime.text = useTime.secondToTime()
            mViewBind.tvKcal.text = NumberUtils.format(kcal, 1, true)
            mViewBind.tvStep.text = realStep.toString()
        }

        //这里处理运动过程数据，获取运动实时状态和实时数据
        sportTimer.subscribe { time ->
            //更新运动时间
            //useTime = time
            useTime = (System.currentTimeMillis() - sportStartTime) / 1000 - pauseTime
            //骑行运动使用时长计算卡路里
            if (sportType == BIKE || sportType == INDOOR_BIKE) {
                kcal = ((LocalSave.weight * 1.11) * useTime / 1000)
            }

            //每隔5秒采集数据
            if (useTime.toInt() % itemInterval.toInt() == 0 && !isPause) {
                //"Time=${time}, 运动时长=${useTime}, 暂停时长=${pauseTime}".logB()
                //获取sdk实时运动数据
                ServiceSdkCommandV2.getSportLiveData(
                    sportType,
                    sportId,
                    object : SportLiveDataCallback {
                        override fun onSportLiveData(liveData: SportLiveData) {
                            if (liveData.sportStatus == 1 || liveData.sportStatus == 2) {
                                sportRecordAda.addData(liveData.toString())
                                mViewBind.rvSportData.scrollToPosition(sportRecordAda.data.size - 1)
                                //更新运动界面数据
                                km = liveData.dist.toDouble() / 1000
                                kcal = liveData.calorie.toDouble()
                                realStep = liveData.step.toInt()
                            } else if (liveData.sportStatus == 3) {
                                "设备已停止运动，停止运动计时，结束运动。".logBx()
                                ToastUtils.showLong("${R.string.str_device_stopped_sport.resString()}")
                                ServiceSdkCommandV2.getSportRecord(object : SportRecordCallback {
                                    override fun onSportRecord(record: SportRecord?) {
                                        record?.let { showSportRecordUI(it) }
                                    }
                                })
                                sportTimer.stop(true)
                            } else {

                            }
                        }
                    }
                )
            }
        }

        sportTimer.finish {
            isPause = true
            mViewBind.btStartSport.isEnabled = false
            mViewBind.btPauseSport.isEnabled = false
            mViewBind.btResumeSport.isEnabled = false
            mViewBind.btStopSport.isEnabled = false
        }
        sportTimer.start()
    }

    private fun showSportRecordUI(record: SportRecord) {
        record.logIx("运动综合数据")
        runOnUiThread {
            mViewBind.tvSportDataType.text = "${R.string.str_device_comprehensive_sports_data.resString()}"
            val recordString = mutableListOf<String>()
            recordString.add(record.toString())
            recordString.addAll(record.sportDetails.map { it.toString() }.toMutableList())
            sportRecordAda.setList(recordString)
            mViewBind.btStartSport.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceSdkCommandV2.onDestory()
    }

    private fun addSleepData() : MutableList<SleepRecord> {

        var sleepData: MutableList<SleepRecord> = mutableListOf()
        sleepData.clear()

        //20240607
        sleepData.add(SleepRecord().apply {
            this.device = ""
            this.date = 1717689600000
            this.sleepType = 0
            this.measureType = 0
            this.sleepId = 1717689180000
            this.startTime = 1717689180000
            this.endTime = 1717714800000
            this.deepDuration = 128
            this.lightDuration = 175
            this.remDuration = 111
            this.awakeDuration = 13
            this.totalDuration = 414
            this.awakeTimes = 1

            //睡眠详细数据列表
            this.sleepDetails = mutableListOf()
            this.sleepDetails.add(SleepStage(1717689180000, 1))
            this.sleepDetails.add(SleepStage(1717690740000, 2))
            this.sleepDetails.add(SleepStage(1717691520000, 1))
            this.sleepDetails.add(SleepStage(1717691580000, 5))
            this.sleepDetails.add(SleepStage(1717693980000, 1))
            this.sleepDetails.add(SleepStage(1717694400000, 2))
            this.sleepDetails.add(SleepStage(1717696080000, 1))
            this.sleepDetails.add(SleepStage(1717696440000, 5))
            this.sleepDetails.add(SleepStage(1717697460000, 1))
            this.sleepDetails.add(SleepStage(1717697820000, 5))
            this.sleepDetails.add(SleepStage(1717698240000, 1))
            this.sleepDetails.add(SleepStage(1717698600000, 2))
            this.sleepDetails.add(SleepStage(1717698960000, 5))
            this.sleepDetails.add(SleepStage(1717699500000, 1))
            this.sleepDetails.add(SleepStage(1717700640000, 2))
            this.sleepDetails.add(SleepStage(1717701480000, 1))
            this.sleepDetails.add(SleepStage(1717701960000, 2))
            this.sleepDetails.add(SleepStage(1717702320000, 1))
            this.sleepDetails.add(SleepStage(1717702800000, 2))
            this.sleepDetails.add(SleepStage(1717703640000, 1))
            this.sleepDetails.add(SleepStage(1717704360000, 2))
            this.sleepDetails.add(SleepStage(1717704900000, 1))
            this.sleepDetails.add(SleepStage(1717705200000, 5))
            this.sleepDetails.add(SleepStage(1717705800000, 1))
            this.sleepDetails.add(SleepStage(1717707180000, 5))
            this.sleepDetails.add(SleepStage(1717708200000, 1))
            this.sleepDetails.add(SleepStage(1717708560000, 2))
            this.sleepDetails.add(SleepStage(1717710480000, 1))
            this.sleepDetails.add(SleepStage(1717711080000, 2))
            this.sleepDetails.add(SleepStage(1717711440000, 1))
            this.sleepDetails.add(SleepStage(1717712340000, 3))
            this.sleepDetails.add(SleepStage(1717713120000, 1))
            this.sleepDetails.add(SleepStage(1717714140000, 5))
            this.sleepDetails.add(SleepStage(1717714800000, 3))
        })

        return sleepData

    }

    private fun addHealthData() : MutableList<HealthRecord> {
        var healthData: MutableList<HealthRecord> = mutableListOf()

        //20240607
        healthData.add(HealthRecord("", 1717689600000, 1717690110000, 69, 39, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717690410000, 68, 32, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717690710000, 68, 26, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717691010000, 63, 20, 0, 36.4f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717691310000, 68, 34, 0, 36.4f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717691610000, 85, 27, 0, 36.4f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717691910000, 61, 21, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717692210000, 64, 35, 0, 36.9f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717692510000, 70, 29, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717692864000, 59, 69, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717693110000, 69, 69, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717693410000, 64, 69, 0, 36.9f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717693710000, 68, 69, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717694010000, 70, 69, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717694310000, 67, 69, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717694664000, 66, 70, 0, 37.1f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717694910000, 69, 70, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717695210000, 68, 70, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717695510000, 65, 70, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717695810000, 68, 70, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717696110000, 70, 70, 0, 37.0f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717696508000, 67, 71, 97, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717696710000, 73, 71, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717697464000, 61, 71, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717697764000, 69, 71, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717698064000, 65, 71, 0, 36.8f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717698418000, 63, 72, 0, 36.9f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717698664000, 69, 72, 0, 36.9f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717698965000, 67, 72, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717699265000, 67, 72, 0, 36.8f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717699565000, 63, 72, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717699865000, 63, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717700219000, 62, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717700465000, 63, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717700765000, 62, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717701065000, 62, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717701365000, 64, 72, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717701665000, 62, 72, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717702019000, 60, 73, 0, 36.8f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717702265000, 60, 73, 0, 36.8f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717702565000, 61, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717702865000, 59, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717703165000, 60, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717703465000, 59, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717703863000, 66, 73, 98, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717704065000, 58, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717704365000, 61, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717704665000, 63, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717704965000, 64, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717705265000, 67, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717705619000, 63, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717705865000, 67, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717706465000, 70, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717706765000, 72, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717707065000, 61, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717707419000, 63, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717707665000, 63, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717707965000, 60, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717708265000, 59, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717708565000, 57, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717708865000, 55, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717709219000, 56, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717709465000, 57, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717709765000, 56, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717710065000, 55, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717710365000, 56, 73, 0, 36.5f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717710665000, 58, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717711063000, 60, 73, 98, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717711265000, 74, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717711565000, 55, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717711865000, 63, 73, 0, 36.6f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717712165000, 65, 73, 0, 36.7f, 0.0f, 0, 0f, 0f))
        healthData.add(HealthRecord("", 1717689600000, 1717712867000, 55, 73, 0, 36.5f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717713365000, 58, 73, 0, 36.5f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717713664000, 61, 73, 0, 36.4f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717713964000, 66, 73, 0, 36.3f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717714264000, 69, 73, 0, 36.4f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717714618000, 60, 74, 0, 36.4f, 0.0f, 18, 0.6f, 12.06f))
        healthData.add(HealthRecord("", 1717689600000, 1717714904000, 82, 74, 0, 36.3f, 0.0f, 91, 3.1f, 20.01f))
        healthData.add(HealthRecord("", 1717689600000, 1717716988000, 82, 74, 0, 36.2f, 0.0f, 137, 4.6f, 9.87f))
        healthData.add(HealthRecord("", 1717689600000, 1717717682000, 76, 74, 0, 36.2f, 0.0f, 382, 13.1f, 10.18f))
        healthData.add(HealthRecord("", 1717689600000, 1717718188000, 87, 74, 0, 36.3f, 0.0f, 1252, 42.9f, 19.64f))
        healthData.add(HealthRecord("", 1717689600000, 1717718488000, 77, 0, 0, 36.4f, 0.0f, 1252, 42.9f, 19.64f))

        return healthData
    }


    //添加
    private fun addSportData() : MutableList<SportRecord> {

        var sportData: MutableList<SportRecord> = mutableListOf()
        sportData.clear()

        //20240606
        sportData.add(SportRecord().apply {
            this.device = ""
            this.date = 1717603200000
            this.sportId = 1717633825000
            this.sportType = 5
            this.startTime = 1717633825000
            this.endTime = 1717634211000
            this.duration = 386
            this.interval = 9
            this.steps = 1346
            this.calorie = 46.1f
            this.distance = 723.23f
            this.avgPace = 533f
            this.minPace = 487f
            this.maxPace = 598f
            this.avgSpeed = 6.47f
            this.maxSpeed = 6.62f
            this.minSpeed = 5.76f
            this.avgCadence = 209
            this.maxCadence = 231
            this.minCadence = 164
            this.avgHr = 78
            this.maxHr = 97
            this.minHr = 69

            //睡眠详细数据列表，目前暂不计算运动过程细节，可以为空队列
            this.sportDetails = mutableListOf()
        })

        return sportData
    }

    //添加
    private fun addHealthScore() : MutableList<HealthScore> {
        var healthScore: MutableList<HealthScore> = mutableListOf()
        healthScore.clear()

        //20240606
        healthScore.add(HealthScore().apply {
            this.date = 1717603200000
            this.activityScore = ActivityScore().apply {
                this.activityScore = 73
                this.stayActivityScore = 76
                this.meatDailyGoalScore = 87
                this.exerciseFrequencyScore = 43
                this.exerciseVolumeScore = 52
            }
            this.sleepScore = SleepScore().apply {
                this.sleepScore = 83
                this.sleepDurationScore = 92
                this.sleepEfficiencyScore = 83
                this.deepSleepScore = 87
                this.remSleepScore = 72
                this.restfulnessScore = 38
                this.sleepBreathScore = 78
            }
            this.readinessScore = ReadinessScore().apply {
                this.readinessScore = 79
                this.previousActivityScore = 67
                this.previousSleepScore = 81
                this.recoveryIndexScore = 66
                this.temperatureScore = 91
                this.rhrScore = 74
            }
        })

        return healthScore
    }

    private fun startECGMeasureCountDownJob() {
        measureECGCountDownJob?.cancel()
        measureECGCountDownJob = launchMain {
            for (i in 10 downTo 1) {
                delay(1000)
            }
            "startECGMeasureCountDownJob".logIx()
            mViewBind.ecgInitialize.isEnabled = true
            mViewBind.ecgStart.isEnabled = false
            mViewBind.ecgStop.isEnabled = false
        }
    }

    private fun requestPermission() {
        locationRequest.launch(
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        )
    }
}
