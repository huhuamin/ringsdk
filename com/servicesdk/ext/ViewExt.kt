package com.servicesdk.ext

import android.view.View
import com.blankj.utilcode.util.ToastUtils
import com.eiot.aizo.ext.otherwise
import com.eiot.aizo.ext.yes
import com.eiot.ringsdk.be.DeviceManager
import com.servicesdk.demo.R


fun View?.visOrGone(isBis: Boolean) {
    this?.visibility = if (isBis) View.VISIBLE else View.GONE
}

fun View.checkConnectClick(clickInvoke: () -> Unit){
    this.setOnClickListener {
        DeviceManager.isConnect().yes {
            clickInvoke.invoke()
        }.otherwise {
            ToastUtils.showLong(R.string.str_device_connect_tip)
        }
    }
}
