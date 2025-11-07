package com.servicesdk.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.blankj.utilcode.util.ToastUtils
import com.eiot.ringsdk.ServiceSdkCommandV2
import com.eiot.ringsdk.ext.logBx
import com.jiaqiao.product.util.PLifeScope

object CallManager {

    const val TAG = "CallManager"
    private var mPhoneStateListener: MyPhoneStateListener? = null
    private var callComing = false  //是否接到来电

    //检查是否满足条件注册电话监听
    fun checkAndRegisterCallListener(context: Context) {
        if (hasCallPermission(context)) {
            registerCallListener(context)
        } else {
            "是否有来电权限 ${hasCallPermission(context)}".logBx(
            )
        }
    }

    //注册电话监听
    private fun registerCallListener(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (mPhoneStateListener == null) {
                mPhoneStateListener = MyPhoneStateListener()
            }
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            "已注册来电监听".logBx()
            ToastUtils.showLong("register success")
        } catch (e: Exception) {
            mPhoneStateListener = null
        }
    }

    //取消注册电话监听
    fun unregisterCallListener(context: Context) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE)
        mPhoneStateListener = null
    }


    //电话监听
    private class MyPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            "state  $state  phoneNumber$phoneNumber".logBx(TAG)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> callHangUp()
                TelephonyManager.CALL_STATE_OFFHOOK -> offHook()  //接听
                TelephonyManager.CALL_STATE_RINGING -> callComing()
            }
        }
    }

    //发送来电报文
    private fun callComing() {
        callComing = true
        ServiceSdkCommandV2.vibrateCallEvent(1)
    }

    //挂断或者空闲，注册之后会立马回调一个空闲
    private fun callHangUp() {
        if (callComing) {  //接到来电过才算挂断
            //发送报文
            ServiceSdkCommandV2.vibrateCallEvent(3)
            callComing = false
        }
    }

    private fun offHook() {
        ServiceSdkCommandV2.vibrateCallEvent(2)
    }

    //是否有电话状态权限
    fun hasCallPermission(context: Context) = ActivityCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
}