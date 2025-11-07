package com.servicesdk.base

import android.app.Application
import com.bumptech.glide.load.engine.executor.GlideExecutor.UncaughtThrowableStrategy.LOG
import com.eiot.ringsdk.ServiceSdkCommandV2
import com.eiot.ringsdk.ext.logEx


class AppApplication : Application() {

    companion object {
        lateinit var mInstance: AppApplication

        @JvmStatic
        fun getInstance(): AppApplication {
            return mInstance
        }
    }

    override fun onCreate() {
        super.onCreate()
        mInstance = this
        ServiceSdkCommandV2.init(
            this,
            region = 1,
            version = "1.0.0",
            name = "SDK_DEMO_APP",
            id = "com.eiot.sdk.demoapp",
            country = "CN",
            language = "ZH",
            debugging = true)
        //增加日志
//        println("init sdk success")

        logEx("init sdk success")


    }
}
