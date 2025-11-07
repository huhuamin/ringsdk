package com.servicesdk.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

abstract class BaseVBActivity<VB : ViewBinding> : ThemeActivity() {


    lateinit var mViewBind: VB
    var dataBindView: View? = null


    /**
     * 初始化view
     */
    abstract fun initView(savedInstanceState: Bundle?)

    open fun onBindViewClick() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewBind()
        setContentView(dataBindView)
        dataBindView?.post {
            initView(savedInstanceState)
            onBindViewClick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initViewBind() {
        val superClass = javaClass.genericSuperclass
        val aClass = (superClass as ParameterizedType).actualTypeArguments[0] as Class<*>
        val method = aClass.getDeclaredMethod("inflate", LayoutInflater::class.java)
        mViewBind = method.invoke(null, layoutInflater) as VB
        dataBindView = mViewBind.root
    }


    private fun <VM> getVmClazz(obj: Any): VM {
        return (obj.javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[1] as VM
    }



}